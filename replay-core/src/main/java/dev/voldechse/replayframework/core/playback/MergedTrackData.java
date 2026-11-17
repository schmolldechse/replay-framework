package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.core.playback.buffer.PrefetchPlanner;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.SeekPoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * The playback data of several tracks as one timeline.
 *
 * <p>All tracks of a replay share their checkpoint barriers: every track has
 * one checkpoint per barrier, with the same ordinal. The timeline seeks on
 * one track's index, the reference; a request for one of its checkpoints is
 * answered with the checkpoint of the same ordinal of every track. Frames of
 * all tracks are merged by time, server tick and sequence, which are unique
 * across tracks within a server tick.</p>
 */
final class MergedTrackData implements PlaybackTimeline.PlaybackData {

    private static final Comparator<RawPacketFrame> FRAME_ORDER =
            Comparator.comparingLong(RawPacketFrame::elapsedNanos)
                    .thenComparingLong(RawPacketFrame::serverTick)
                    .thenComparingInt(RawPacketFrame::sequence);

    private final List<Track> tracks;

    MergedTrackData(List<Track> tracks) {
        this.tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        if (this.tracks.isEmpty()) {
            throw new IllegalArgumentException("at least one track is required");
        }
    }

    @Override
    public CompletionStage<PlaybackTimeline.BufferProgress> ensureAvailable(
            Duration position, PlaybackSpeed speed, PrefetchPlanner.Direction direction) {
        List<CompletableFuture<PlaybackTimeline.BufferProgress>> loads = tracks.stream()
                .map(track -> track.data().ensureAvailable(position, speed, direction)
                        .toCompletableFuture())
                .toList();
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> combine(loads.stream().map(CompletableFuture::join).toList()));
    }

    @Override
    public CompletionStage<List<RawPacketFrame>> checkpointFrames(SeekPoint point) {
        return merge(track -> track.data().checkpointFrames(track.anchor(point)));
    }

    @Override
    public CompletionStage<List<RawPacketFrame>> framesBetween(
            Duration startInclusive, Duration endInclusive) {
        return merge(track -> track.data().framesBetween(startInclusive, endInclusive));
    }

    @Override
    public CompletionStage<List<RawPacketFrame>> framesAfter(
            SeekPoint checkpoint, Duration endExclusive) {
        return merge(track -> track.data().framesAfter(track.anchor(checkpoint), endExclusive));
    }

    @Override
    public PlaybackTimeline.BufferProgress progress(Duration position) {
        return combine(tracks.stream().map(track -> track.data().progress(position)).toList());
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (Track track : tracks) {
            try {
                track.data().close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private CompletionStage<List<RawPacketFrame>> merge(
            Function<Track, CompletionStage<List<RawPacketFrame>>> read) {
        List<CompletableFuture<List<RawPacketFrame>>> reads;
        try {
            reads = tracks.stream().map(track -> read.apply(track).toCompletableFuture()).toList();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    if (reads.size() == 1) {
                        return reads.getFirst().join();
                    }
                    List<RawPacketFrame> frames = new ArrayList<>();
                    reads.forEach(part -> frames.addAll(part.join()));
                    frames.sort(FRAME_ORDER);
                    return List.copyOf(frames);
                });
    }

    /** The least buffered track decides how much the timeline has. */
    private static PlaybackTimeline.BufferProgress combine(
            List<PlaybackTimeline.BufferProgress> progress) {
        Duration behind = progress.stream()
                .map(PlaybackTimeline.BufferProgress::bufferedBehind)
                .min(Comparator.naturalOrder())
                .orElse(Duration.ZERO);
        Duration ahead = progress.stream()
                .map(PlaybackTimeline.BufferProgress::bufferedAhead)
                .min(Comparator.naturalOrder())
                .orElse(Duration.ZERO);
        boolean satisfied = progress.stream()
                .allMatch(PlaybackTimeline.BufferProgress::minimumResumeSatisfied);
        return new PlaybackTimeline.BufferProgress(behind, ahead, satisfied);
    }

    /**
     * One track of the timeline.
     *
     * @param data the track's own playback data
     * @param anchors the track's checkpoint seek point per checkpoint ordinal
     */
    record Track(PlaybackTimeline.PlaybackData data, Map<Integer, SeekPoint> anchors) {

        Track {
            Objects.requireNonNull(data, "data");
            anchors = Map.copyOf(Objects.requireNonNull(anchors, "anchors"));
        }

        /** Creates a track whose anchors are the first seek point of every checkpoint ordinal. */
        static Track of(PlaybackTimeline.PlaybackData data, ReplayIndex index) {
            Map<Integer, SeekPoint> anchors = new HashMap<>();
            for (SeekPoint point : index.points()) {
                anchors.putIfAbsent(point.checkpointOrdinal(), point);
            }
            return new Track(data, anchors);
        }

        private SeekPoint anchor(SeekPoint reference) {
            SeekPoint anchor = anchors.get(reference.checkpointOrdinal());
            if (anchor == null) {
                throw new IllegalStateException(
                        "track has no checkpoint " + reference.checkpointOrdinal());
            }
            return anchor;
        }
    }
}
