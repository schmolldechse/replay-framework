package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.api.playback.PlaybackBufferOptions;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactReader;
import dev.voldechse.replayframework.core.playback.buffer.PlaybackBuffer;
import dev.voldechse.replayframework.core.playback.cache.SegmentCache;
import dev.voldechse.replayframework.format.CorruptReplayArtifactException;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import dev.voldechse.replayframework.format.ReplayCheckpointReader;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.ReplayIndexReader;
import dev.voldechse.replayframework.format.ReplayManifest;
import dev.voldechse.replayframework.format.ReplaySegmentReader;
import dev.voldechse.replayframework.format.SeekPoint;
import dev.voldechse.replayframework.format.TrackDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens the playback data of one track: reads and checks its index, maps its
 * segments and checkpoints, and builds the buffer and the checkpoint cache
 * that playback reads it through.
 *
 * <p>Opening has two phases. {@link #prepare} fetches and checks the
 * artifacts and holds no resources; {@link #open(PreparedTrack,
 * PlaybackBufferOptions)} then builds the buffer. The scene prepares all its
 * tracks before it opens any, so a failed track leaves no buffer behind.</p>
 */
final class TrackOpener {

    private static final Pattern SEGMENT_PATH =
            Pattern.compile("segments/([0-9]+)\\.segment");
    private static final Pattern CHECKPOINT_PATH =
            Pattern.compile("checkpoints/([0-9]+)\\.checkpoint");

    private final ReplayArtifactReader artifactReader;
    private final SegmentCache segmentCache;
    private final ReplayIndexReader indexReader;
    private final ReplayCheckpointReader checkpointReader;
    private final ReplaySegmentReader segmentReader;
    private final Executor ioExecutor;

    TrackOpener(
            ReplayArtifactReader artifactReader,
            SegmentCache segmentCache,
            ReplayIndexReader indexReader,
            ReplayCheckpointReader checkpointReader,
            ReplaySegmentReader segmentReader,
            Executor ioExecutor) {
        this.artifactReader = Objects.requireNonNull(artifactReader, "artifactReader");
        this.segmentCache = Objects.requireNonNull(segmentCache, "segmentCache");
        this.indexReader = Objects.requireNonNull(indexReader, "indexReader");
        this.checkpointReader = Objects.requireNonNull(checkpointReader, "checkpointReader");
        this.segmentReader = Objects.requireNonNull(segmentReader, "segmentReader");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /** Prepares and opens a track in one go, as for a state track read on demand. */
    CompletionStage<OpenedTrack> open(
            ReplayArtifactReader.VerifiedReplay verified,
            TrackDescriptor track,
            PlaybackBufferOptions options) {
        return prepare(verified, track).thenApply(prepared -> open(prepared, options));
    }

    /** Fetches and checks a track's index, segments and checkpoints without opening anything. */
    CompletionStage<PreparedTrack> prepare(ReplayArtifactReader.VerifiedReplay verified, TrackDescriptor track) {
        ReplayManifest manifest = verified.manifest();
        return fetchAndReadIndex(verified, track).thenApply(index -> new PreparedTrack(
                verified,
                track,
                index,
                buildSegmentRefs(manifest, track, index),
                buildCheckpointFiles(manifest, track, index)));
    }

    /**
     * The checked artifacts of one track.
     *
     * @param verified the replay the track belongs to
     * @param track the track
     * @param index its seek index
     * @param segments its segments with their time windows
     * @param checkpoints its checkpoint artifacts by ordinal
     */
    record PreparedTrack(
            ReplayArtifactReader.VerifiedReplay verified,
            TrackDescriptor track,
            ReplayIndex index,
            List<SegmentCache.SegmentRef> segments,
            Map<Integer, ReplayManifest.ArtifactFile> checkpoints) {
    }

    /**
     * An opened track.
     *
     * @param descriptor the track
     * @param index its seek index
     * @param buffer the buffer its frames are read through; the owner closes it
     * @param data the track's frames and checkpoints for playback
     */
    record OpenedTrack(
            TrackDescriptor descriptor,
            ReplayIndex index,
            PlaybackBuffer buffer,
            MergedTrackData.Track data) {
    }

    /** Builds the buffer and checkpoint cache of a prepared track. */
    OpenedTrack open(PreparedTrack prepared, PlaybackBufferOptions options) {
        Objects.requireNonNull(options, "options");
        PlaybackBuffer buffer = new PlaybackBuffer(
                prepared.verified(),
                prepared.segments(),
                prepared.track(),
                artifactReader,
                segmentCache,
                segmentReader,
                options,
                ioExecutor);
        Map<SeekPoint, CompletionStage<List<RawPacketFrame>>> cache = new ConcurrentHashMap<>();
        MergedTrackData.Track data = MergedTrackData.Track.of(
                PlaybackTimeline.adapt(buffer, point -> cachedCheckpoint(cache, prepared, point)),
                prepared.index());
        return new OpenedTrack(prepared.track(), prepared.index(), buffer, data);
    }

    private CompletionStage<ReplayIndex> fetchAndReadIndex(
            ReplayArtifactReader.VerifiedReplay verified,
            TrackDescriptor track) {
        ReplayManifest.ArtifactFile indexArtifact = verified.manifest().files(track).stream()
                .filter(file -> file.type() == ReplayManifest.ArtifactType.INDEX)
                .findFirst()
                .orElseThrow(() -> new CompletionException(
                        new CorruptReplayArtifactException("track has no index artifact")));

        return CompletableFuture.supplyAsync(
                        () -> createTemporaryFile("replay-index-", ".bin"),
                        ioExecutor)
                .thenCompose(target -> artifactReader.fetchVerified(
                                verified,
                                indexArtifact,
                                target)
                        .thenApplyAsync(this::readIndex, ioExecutor)
                        .whenComplete((ignored, failure) -> deleteQuietly(target)));
    }

    private CompletionStage<List<RawPacketFrame>> cachedCheckpoint(
            Map<SeekPoint, CompletionStage<List<RawPacketFrame>>> cache,
            PreparedTrack track,
            SeekPoint point) {
        CompletableFuture<List<RawPacketFrame>> loading = new CompletableFuture<>();
        CompletionStage<List<RawPacketFrame>> existing = cache.putIfAbsent(point, loading);
        if (existing != null) {
            return existing;
        }

        fetchAndDecodeCheckpoint(track, point)
                .whenComplete((frames, failure) -> {
                    if (failure != null) {
                        cache.remove(point, loading);
                        loading.completeExceptionally(failure);
                    } else {
                        loading.complete(frames);
                    }
                });
        return loading;
    }

    private CompletionStage<List<RawPacketFrame>> fetchAndDecodeCheckpoint(PreparedTrack track, SeekPoint point) {
        ReplayManifest.ArtifactFile artifact = track.checkpoints().get(point.checkpointOrdinal());
        if (artifact == null) {
            return CompletableFuture.failedFuture(new CorruptReplayArtifactException(
                    "index references missing checkpoint ordinal: " + point.checkpointOrdinal()));
        }
        return CompletableFuture.supplyAsync(
                        () -> createTemporaryFile("replay-checkpoint-", ".bin"),
                        ioExecutor)
                .thenCompose(target -> artifactReader.fetchVerified(track.verified(), artifact, target)
                        .thenApplyAsync(fetched -> readCheckpoint(fetched, track.track(), point), ioExecutor)
                        .whenComplete((ignored, failure) -> deleteQuietly(target)));
    }

    private ReplayIndex readIndex(Path source) {
        try {
            ReplayIndex index = indexReader.read(source);
            if (index.points().isEmpty()) {
                throw new CompletionException(new CorruptReplayArtifactException(
                        "replay index must contain at least one seek point"));
            }
            return index;
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private List<RawPacketFrame> readCheckpoint(
            Path source,
            TrackDescriptor track,
            SeekPoint point) {
        try {
            ReplayCheckpoint checkpoint = checkpointReader.read(source, track);
            if (checkpoint.ordinal() != point.checkpointOrdinal()
                    || checkpoint.elapsedNanos() != point.elapsedNanos()) {
                throw new CompletionException(new CorruptReplayArtifactException(
                        "checkpoint does not match its seek point"));
            }
            return checkpoint.initializationFrames();
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    /**
     * The segments of one track with their time windows. A track may have no
     * segments; then its index holds only checkpoint anchors.
     */
    static List<SegmentCache.SegmentRef> buildSegmentRefs(
            ReplayManifest manifest,
            TrackDescriptor track,
            ReplayIndex index) {
        List<ReplayManifest.ArtifactFile> artifacts = manifest.files(track).stream()
                .filter(file -> file.type() == ReplayManifest.ArtifactType.SEGMENT)
                .sorted(Comparator.comparing(ReplayManifest.ArtifactFile::path))
                .toList();
        if (artifacts.isEmpty()) {
            for (SeekPoint point : index.points()) {
                if (point.elapsedNanos() > manifest.durationNanos()
                        || point.segmentOrdinal() != 0 || point.frameOffset() != 0L) {
                    throw corrupt("index of a track without segments references a segment");
                }
            }
            return List.of();
        }

        Map<Integer, ReplayManifest.ArtifactFile> filesByOrdinal = new HashMap<>();
        for (ReplayManifest.ArtifactFile artifact : artifacts) {
            int ordinal = parseOrdinal(artifact.trackPath(), SEGMENT_PATH, "segment");
            if (filesByOrdinal.putIfAbsent(ordinal, artifact) != null) {
                throw corrupt("duplicate segment ordinal: " + ordinal);
            }
        }

        Map<Integer, List<SeekPoint>> framePointsBySegment = new HashMap<>();
        Set<Integer> seenCheckpointOrdinals = new HashSet<>();
        for (SeekPoint point : index.points()) {
            if (point.elapsedNanos() > manifest.durationNanos()) {
                throw corrupt("index point exceeds replay duration");
            }
            if (!filesByOrdinal.containsKey(point.segmentOrdinal())) {
                throw corrupt("index references a missing segment artifact");
            }
            // The first point for each checkpoint ordinal is its seek anchor,
            // not a frame contained by the referenced segment. An anchor can
            // intentionally point at the sealed end of the preceding segment.
            if (seenCheckpointOrdinals.add(point.checkpointOrdinal())) {
                continue;
            }
            framePointsBySegment.computeIfAbsent(
                            point.segmentOrdinal(), ignored -> new ArrayList<>())
                    .add(point);
        }

        List<SegmentCache.SegmentRef> result = new ArrayList<>(filesByOrdinal.size());
        long previousEnd = 0L;
        for (Map.Entry<Integer, ReplayManifest.ArtifactFile> entry : filesByOrdinal.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            int ordinal = entry.getKey();
            List<SeekPoint> points = framePointsBySegment.remove(ordinal);
            if (points == null || points.isEmpty()) {
                throw corrupt("segment has no frame index point: " + ordinal);
            }
            long start = points.stream().mapToLong(SeekPoint::elapsedNanos).min().orElseThrow();
            long end = points.stream().mapToLong(SeekPoint::elapsedNanos).max().orElseThrow();
            if (end < start || start < previousEnd) {
                throw corrupt("segment time windows are not ordered");
            }
            result.add(new SegmentCache.SegmentRef(ordinal, entry.getValue(), start, end));
            previousEnd = end;
        }
        if (!framePointsBySegment.isEmpty()) {
            throw corrupt("index references a missing segment artifact");
        }
        return List.copyOf(result);
    }

    private static Map<Integer, ReplayManifest.ArtifactFile> buildCheckpointFiles(
            ReplayManifest manifest,
            TrackDescriptor track,
            ReplayIndex index) {
        Map<Integer, ReplayManifest.ArtifactFile> result = new HashMap<>();
        for (ReplayManifest.ArtifactFile artifact : manifest.files(track)) {
            if (artifact.type() != ReplayManifest.ArtifactType.CHECKPOINT) {
                continue;
            }
            int ordinal = parseOrdinal(artifact.trackPath(), CHECKPOINT_PATH, "checkpoint");
            if (result.putIfAbsent(ordinal, artifact) != null) {
                throw corrupt("duplicate checkpoint ordinal: " + ordinal);
            }
        }
        for (SeekPoint point : index.points()) {
            if (!result.containsKey(point.checkpointOrdinal())) {
                throw corrupt("index references a missing checkpoint artifact: "
                        + point.checkpointOrdinal());
            }
        }
        return Map.copyOf(result);
    }

    private static int parseOrdinal(String path, Pattern pattern, String type) {
        Matcher matcher = pattern.matcher(path);
        if (!matcher.matches()) {
            throw corrupt("invalid " + type + " artifact path: " + path);
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw corrupt(type + " ordinal is outside the supported range: " + path);
        }
    }

    private static CompletionException corrupt(String message) {
        return new CompletionException(new CorruptReplayArtifactException(message));
    }

    private static Path createTemporaryFile(String prefix, String suffix) {
        try {
            return Files.createTempFile(prefix, suffix);
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A temporary-file cleanup failure must not replace the playback result.
        }
    }
}
