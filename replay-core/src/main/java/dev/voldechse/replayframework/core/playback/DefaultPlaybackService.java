package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.playback.PlaybackRequest;
import dev.voldechse.replayframework.api.playback.PlaybackService;
import dev.voldechse.replayframework.api.playback.PlaybackSession;
import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactReader;
import dev.voldechse.replayframework.core.diagnostics.ReplayDiagnostics;
import dev.voldechse.replayframework.core.playback.buffer.PlaybackBuffer;
import dev.voldechse.replayframework.core.playback.cache.SegmentCache;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.format.CorruptReplayArtifactException;
import dev.voldechse.replayframework.format.CorruptReplaySegmentException;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import dev.voldechse.replayframework.format.ReplayCheckpointReader;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.ReplayIndexReader;
import dev.voldechse.replayframework.format.ReplayManifest;
import dev.voldechse.replayframework.format.ReplaySegmentReader;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.SeekPoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.entity.Player;

/** Opens and manages one isolated playback graph per viewer. */
final class DefaultPlaybackService implements PlaybackService {

    private static final Pattern SEGMENT_PATH =
            Pattern.compile("segments/([0-9]+)\\.segment");
    private static final Pattern CHECKPOINT_PATH =
            Pattern.compile("checkpoints/([0-9]+)\\.checkpoint");

    private final ReplayRepository replayRepository;
    private final ReplayArtifactReader artifactReader;
    private final ReplayAdapter adapter;
    private final SegmentCache sharedSegmentCache;
    private final ReplayIndexReader indexReader;
    private final ReplayCheckpointReader checkpointReader;
    private final ReplaySegmentReader segmentReader;
    private final Executor ioExecutor;
    private final Executor commandExecutor;
    private final PlaybackCoordinator coordinator;
    private final PlaybackEventDispatcher eventDispatcher;
    private final ReplayDiagnostics diagnostics;

    DefaultPlaybackService(
            ReplayRepository replayRepository,
            ReplayArtifactReader artifactReader,
            ReplayAdapter adapter,
            SegmentCache sharedSegmentCache,
            ReplayIndexReader indexReader,
            ReplayCheckpointReader checkpointReader,
            ReplaySegmentReader segmentReader,
            Executor ioExecutor,
            Executor commandExecutor,
            PlaybackCoordinator coordinator,
            PlaybackEventDispatcher eventDispatcher) {
        this(
                replayRepository,
                artifactReader,
                adapter,
                sharedSegmentCache,
                indexReader,
                checkpointReader,
                segmentReader,
                ioExecutor,
                commandExecutor,
                coordinator,
                eventDispatcher,
                new ReplayDiagnostics());
    }

    DefaultPlaybackService(
            ReplayRepository replayRepository,
            ReplayArtifactReader artifactReader,
            ReplayAdapter adapter,
            SegmentCache sharedSegmentCache,
            ReplayIndexReader indexReader,
            ReplayCheckpointReader checkpointReader,
            ReplaySegmentReader segmentReader,
            Executor ioExecutor,
            Executor commandExecutor,
            PlaybackCoordinator coordinator,
            PlaybackEventDispatcher eventDispatcher,
            ReplayDiagnostics diagnostics) {
        this.replayRepository = Objects.requireNonNull(replayRepository, "replayRepository");
        this.artifactReader = Objects.requireNonNull(artifactReader, "artifactReader");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.sharedSegmentCache = Objects.requireNonNull(sharedSegmentCache, "sharedSegmentCache");
        this.indexReader = Objects.requireNonNull(indexReader, "indexReader");
        this.checkpointReader = Objects.requireNonNull(checkpointReader, "checkpointReader");
        this.segmentReader = Objects.requireNonNull(segmentReader, "segmentReader");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.eventDispatcher = Objects.requireNonNull(eventDispatcher, "eventDispatcher");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override
    public CompletionStage<PlaybackSession> open(PlaybackRequest request) {
        final PlaybackRequest validatedRequest;
        final UUID viewerId;
        final PlaybackSessionId sessionId = PlaybackSessionId.random();
        try {
            validatedRequest = Objects.requireNonNull(request, "request");
            Player viewer = Objects.requireNonNull(validatedRequest.viewer(), "request.viewer");
            viewerId = Objects.requireNonNull(viewer.getUniqueId(), "request.viewer.uniqueId");
            coordinator.reserve(sessionId, viewerId);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        OpenResources resources = new OpenResources();
        CompletionStage<PlaybackSession> opening;
        try {
            opening = loadReplay(validatedRequest)
                    .thenCompose(verified -> prepareSession(
                            sessionId,
                            viewerId,
                            validatedRequest,
                            verified,
                            resources));
        } catch (RuntimeException exception) {
            coordinator.release(sessionId, viewerId);
            return CompletableFuture.failedFuture(exception);
        }

        return opening.whenComplete((ignored, failure) -> {
            if (failure != null) {
                resources.closeAfterFailure();
                coordinator.release(sessionId, viewerId);
            }
        });
    }

    @Override
    public Optional<PlaybackSession> active(PlaybackSessionId id) {
        return coordinator.active(Objects.requireNonNull(id, "id"));
    }

    /** Stops new viewer work and closes all sessions owned by this runtime. */
    CompletionStage<Void> shutdown() {
        diagnostics.beginQuiesce();
        return coordinator.closeAll();
    }

    private CompletionStage<ReplayArtifactReader.VerifiedReplay> loadReplay(
            PlaybackRequest request) {
        return CompletableFuture.supplyAsync(
                        () -> replayRepository.find(request.replayId()),
                        ioExecutor)
                .thenCompose(DefaultPlaybackService::requireStage)
                .thenCompose(optional -> {
                    ReplayRepository.ReplayRow row = optional.orElseThrow(
                            () -> new ReplayRepository.ReplayNotFoundException(request.replayId()));
                    if (row.status() != RecordingStatus.AVAILABLE) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "replay is not available for playback: " + request.replayId()));
                    }
                    return artifactReader.openVerified(request.replayId())
                            .thenApply(verified -> {
                                validateCatalogAndManifest(row, verified.manifest());
                                validateAdapter(verified.manifest());
                                return verified;
                            });
                });
    }

    private CompletionStage<PlaybackSession> prepareSession(
            PlaybackSessionId sessionId,
            UUID viewerId,
            PlaybackRequest request,
            ReplayArtifactReader.VerifiedReplay verified,
            OpenResources resources) {
        ReplayManifest manifest = verified.manifest();
        return fetchAndReadIndex(verified)
                .thenApply(index -> new PreparedArtifacts(
                        index,
                        buildSegmentRefs(manifest, index),
                        buildCheckpointFiles(manifest, index)))
                .thenApply(artifacts -> {
                    PlaybackBuffer buffer = new PlaybackBuffer(
                            verified,
                            artifacts.segments(),
                            artifactReader,
                            sharedSegmentCache,
                            segmentReader,
                            request.bufferOptions(),
                            ioExecutor);
                    resources.buffer.set(buffer);

                    PlaybackBridge bridge = Objects.requireNonNull(
                            adapter.openPlayback(
                                    request.viewer(),
                                    new PlaybackIdentityContext(
                                            viewerId, sessionId.value())),
                            "adapter.openPlayback result");
                    resources.bridge.set(bridge);

                    PlaybackTimeline.Listener listener = timelineListener(
                            sessionId,
                            request.replayId(),
                            viewerId);
                    Map<SeekPoint, CompletionStage<List<RawPacketFrame>>> checkpoints =
                            new ConcurrentHashMap<>();
                    PlaybackTimeline timeline = new PlaybackTimeline(
                            Duration.ofNanos(manifest.durationNanos()),
                            artifacts.index(),
                            PlaybackTimeline.adapt(
                                    buffer,
                                    point -> cachedCheckpoint(
                                            checkpoints,
                                            verified,
                                            artifacts.checkpoints(),
                                            point)),
                            bridge,
                            request.bufferOptions(),
                            commandExecutor,
                            System::nanoTime,
                            listener);
                    bridge.setFailureHandler(timeline::failFromBridge);
                    resources.timeline.set(timeline);
                    return timeline;
                })
                .thenCompose(PlaybackTimeline::prepare)
                .thenApply(snapshot -> {
                    validateInitialSnapshot(snapshot);
                    DefaultPlaybackSession session = new DefaultPlaybackSession(
                            sessionId,
                            request.replayId(),
                            viewerId,
                            resources.timeline.get(),
                            coordinator);
                    coordinator.register(session);
                    diagnostics.playbackStarted(session);
                    return (PlaybackSession) session;
                });
    }

    private CompletionStage<ReplayIndex> fetchAndReadIndex(
            ReplayArtifactReader.VerifiedReplay verified) {
        ReplayManifest.ArtifactFile indexArtifact = verified.manifest().files().stream()
                .filter(file -> file.type() == ReplayManifest.ArtifactType.INDEX)
                .findFirst()
                .orElseThrow(() -> new CompletionException(
                        new CorruptReplayArtifactException("replay has no index artifact")));

        return CompletableFuture.supplyAsync(
                        () -> createTemporaryFile("replay-index-", ".bin"),
                        ioExecutor)
                .thenCompose(target -> artifactReader.fetchVerified(
                                verified,
                                indexArtifact,
                                target)
                        .thenApplyAsync(fetched -> readIndex(fetched), ioExecutor)
                        .whenComplete((ignored, failure) -> deleteQuietly(target)));
    }

    private CompletionStage<List<RawPacketFrame>>
            fetchAndDecodeCheckpoint(
                    ReplayArtifactReader.VerifiedReplay verified,
                    Map<Integer, ReplayManifest.ArtifactFile> checkpointFiles,
                    SeekPoint point) {
        ReplayManifest.ArtifactFile artifact = checkpointFiles.get(point.checkpointOrdinal());
        if (artifact == null) {
            return CompletableFuture.failedFuture(new CorruptReplayArtifactException(
                    "index references missing checkpoint ordinal: " + point.checkpointOrdinal()));
        }
        return CompletableFuture.supplyAsync(
                        () -> createTemporaryFile("replay-checkpoint-", ".bin"),
                        ioExecutor)
                .thenCompose(target -> artifactReader.fetchVerified(verified, artifact, target)
                        .thenApplyAsync(fetched -> readCheckpoint(fetched, point), ioExecutor)
                        .whenComplete((ignored, failure) -> deleteQuietly(target)));
    }

    private CompletionStage<List<RawPacketFrame>> cachedCheckpoint(
            Map<SeekPoint, CompletionStage<List<RawPacketFrame>>> cache,
            ReplayArtifactReader.VerifiedReplay verified,
            Map<Integer, ReplayManifest.ArtifactFile> checkpointFiles,
            SeekPoint point) {
        CompletableFuture<List<RawPacketFrame>> loading = new CompletableFuture<>();
        CompletionStage<List<RawPacketFrame>> existing = cache.putIfAbsent(point, loading);
        if (existing != null) {
            return existing;
        }

        fetchAndDecodeCheckpoint(verified, checkpointFiles, point)
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
            SeekPoint point) {
        try {
            ReplayCheckpoint checkpoint = checkpointReader.read(source);
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

    private List<SegmentCache.SegmentRef> buildSegmentRefs(
            ReplayManifest manifest,
            ReplayIndex index) {
        List<ReplayManifest.ArtifactFile> artifacts = manifest.files().stream()
                .filter(file -> file.type() == ReplayManifest.ArtifactType.SEGMENT)
                .sorted(Comparator.comparing(ReplayManifest.ArtifactFile::path))
                .toList();
        if (artifacts.isEmpty()) {
            throw corrupt("replay has no segment artifacts");
        }

        Map<Integer, ReplayManifest.ArtifactFile> filesByOrdinal = new HashMap<>();
        for (ReplayManifest.ArtifactFile artifact : artifacts) {
            int ordinal = parseOrdinal(artifact.path(), SEGMENT_PATH, "segment");
            if (filesByOrdinal.putIfAbsent(ordinal, artifact) != null) {
                throw corrupt("duplicate segment ordinal: " + ordinal);
            }
        }

        Map<Integer, List<SeekPoint>> pointsBySegment = new HashMap<>();
        SeekPoint initialCheckpoint = new SeekPoint(0L, 0, 0, 0L);
        for (SeekPoint point : index.points()) {
            if (point.elapsedNanos() > manifest.durationNanos()) {
                throw corrupt("index point exceeds replay duration");
            }
            // The bootstrap checkpoint is not a frame in segment zero. Its
            // zero timestamp must not become that segment's lower time bound.
            if (point.equals(initialCheckpoint)) {
                continue;
            }
            pointsBySegment.computeIfAbsent(point.segmentOrdinal(), ignored -> new ArrayList<>())
                    .add(point);
        }

        List<SegmentCache.SegmentRef> result = new ArrayList<>(filesByOrdinal.size());
        long previousEnd = 0L;
        for (Map.Entry<Integer, ReplayManifest.ArtifactFile> entry : filesByOrdinal.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            int ordinal = entry.getKey();
            List<SeekPoint> points = pointsBySegment.remove(ordinal);
            if (points == null || points.isEmpty()) {
                throw corrupt("segment has no index point: " + ordinal);
            }
            long start = points.stream().mapToLong(SeekPoint::elapsedNanos).min().orElseThrow();
            long observedEnd = points.stream().mapToLong(SeekPoint::elapsedNanos).max().orElseThrow();
            long end = ordinal == filesByOrdinal.keySet().stream().max(Integer::compareTo).orElseThrow()
                    ? manifest.durationNanos()
                    : observedEnd;
            if (end < start || observedEnd > manifest.durationNanos() || start < previousEnd) {
                throw corrupt("segment time windows are not ordered");
            }
            result.add(new SegmentCache.SegmentRef(ordinal, entry.getValue(), start, end));
            previousEnd = end;
        }
        if (!pointsBySegment.isEmpty()) {
            throw corrupt("index references a missing segment artifact");
        }
        return List.copyOf(result);
    }

    private Map<Integer, ReplayManifest.ArtifactFile> buildCheckpointFiles(
            ReplayManifest manifest,
            ReplayIndex index) {
        Map<Integer, ReplayManifest.ArtifactFile> result = new HashMap<>();
        for (ReplayManifest.ArtifactFile artifact : manifest.files()) {
            if (artifact.type() != ReplayManifest.ArtifactType.CHECKPOINT) {
                continue;
            }
            int ordinal = parseOrdinal(artifact.path(), CHECKPOINT_PATH, "checkpoint");
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

    private void validateCatalogAndManifest(
            ReplayRepository.ReplayRow row,
            ReplayManifest manifest) {
        if (!row.replayId().equals(ReplayId.parse(manifest.replayId()))
                || !row.adapterId().equals(manifest.adapterId())
                || row.protocolVersion() != manifest.protocolVersion()
                || row.formatRevision() != manifest.formatRevision()
                || row.durationNanos() != manifest.durationNanos()) {
            throw corrupt("catalog and replay manifest are inconsistent");
        }
    }

    private void validateAdapter(ReplayManifest manifest) {
        adapter.verifyDescriptorAndRegistry();
        AdapterDescriptor descriptor = Objects.requireNonNull(adapter.descriptor(), "adapter.descriptor");
        if (!descriptor.adapterId().equals(manifest.adapterId())
                || descriptor.protocolVersion() != manifest.protocolVersion()
                || descriptor.adapterFormatRevision() != manifest.formatRevision()
                || !descriptor.registryFingerprint().equals(manifest.registryFingerprint())) {
            throw new IncompatibleAdapterException(
                    "active adapter is incompatible with replay " + manifest.replayId());
        }
    }

    private PlaybackTimeline.Listener timelineListener(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId) {
        return new PlaybackTimeline.Listener() {
            @Override
            public void onStatusChanged(
                    PlaybackStatus previous,
                    PlaybackStatus current,
                    PlaybackSnapshot snapshot) {
                eventDispatcher.statusChanged(
                        sessionId,
                        replayId,
                        viewerId,
                        previous,
                        current,
                        snapshot);
                updateDiagnostics(sessionId);
            }

            @Override
            public void onSpeedChanged(
                    PlaybackSpeed previous,
                    PlaybackSpeed current,
                    PlaybackSnapshot snapshot) {
                eventDispatcher.speedChanged(
                        sessionId,
                        replayId,
                        viewerId,
                        previous,
                        current,
                        snapshot);
                updateDiagnostics(sessionId);
            }

            @Override
            public void onSeeked(Duration requestedPosition, PlaybackSnapshot snapshot) {
                eventDispatcher.seeked(
                        sessionId,
                        replayId,
                        viewerId,
                        requestedPosition,
                        snapshot.position(),
                        snapshot);
                updateDiagnostics(sessionId);
            }

            @Override
            public void onBufferChanged(PlaybackSnapshot snapshot) {
                eventDispatcher.bufferChanged(sessionId, replayId, viewerId, snapshot);
                updateDiagnostics(sessionId);
            }

            @Override
            public void onCompleted(PlaybackSnapshot snapshot, Throwable failure) {
                eventDispatcher.completed(
                        sessionId,
                        replayId,
                        viewerId,
                        snapshot,
                        failureCode(failure));
                coordinator.release(sessionId, viewerId);
                diagnostics.playbackFinished(sessionId);
            }
        };
    }

    private void updateDiagnostics(PlaybackSessionId sessionId) {
        coordinator.registered(sessionId).ifPresent(diagnostics::playbackUpdated);
    }

    private static Optional<ReplayFailureCode> failureCode(Throwable failure) {
        if (failure == null) {
            return Optional.empty();
        }
        Throwable cause = unwrap(failure);
        if (cause instanceof IncompatibleAdapterException) {
            return Optional.of(ReplayFailureCode.INCOMPATIBLE_ADAPTER);
        }
        if (cause instanceof CorruptReplayArtifactException
                || cause instanceof CorruptReplaySegmentException) {
            return Optional.of(ReplayFailureCode.CORRUPT_DATA);
        }
        if (cause instanceof IOException) {
            return Optional.of(ReplayFailureCode.STORAGE_ERROR);
        }
        return Optional.of(ReplayFailureCode.INTERNAL_ERROR);
    }

    private static void validateInitialSnapshot(PlaybackSnapshot snapshot) {
        if (snapshot.status() != PlaybackStatus.PAUSED || !snapshot.position().isZero()) {
            throw new IllegalStateException("initial playback view is not paused at zero");
        }
    }

    private static int parseOrdinal(String path, Pattern pattern, String type) {
        Matcher matcher = pattern.matcher(path);
        if (!matcher.matches()) {
            throw corrupt("invalid " + type + " artifact path: " + path);
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw corrupt("" + type + " ordinal is outside the supported range: " + path);
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

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> CompletionStage<T> requireStage(CompletionStage<T> stage) {
        return Objects.requireNonNull(stage, "completion stage");
    }

    private static final class PreparedArtifacts {
        private final ReplayIndex index;
        private final List<SegmentCache.SegmentRef> segments;
        private final Map<Integer, ReplayManifest.ArtifactFile> checkpoints;

        private PreparedArtifacts(
                ReplayIndex index,
                List<SegmentCache.SegmentRef> segments,
                Map<Integer, ReplayManifest.ArtifactFile> checkpoints) {
            this.index = index;
            this.segments = segments;
            this.checkpoints = checkpoints;
        }

        private ReplayIndex index() {
            return index;
        }

        private List<SegmentCache.SegmentRef> segments() {
            return segments;
        }

        private Map<Integer, ReplayManifest.ArtifactFile> checkpoints() {
            return checkpoints;
        }
    }

    private static final class OpenResources {
        private final AtomicReference<PlaybackBuffer> buffer = new AtomicReference<>();
        private final AtomicReference<PlaybackBridge> bridge = new AtomicReference<>();
        private final AtomicReference<PlaybackTimeline> timeline = new AtomicReference<>();

        private void closeAfterFailure() {
            PlaybackTimeline currentTimeline = timeline.get();
            if (currentTimeline != null) {
                currentTimeline.closeAsync().exceptionally(ignored -> null);
                return;
            }
            PlaybackBuffer currentBuffer = buffer.get();
            if (currentBuffer != null) {
                currentBuffer.close();
            }
            PlaybackBridge currentBridge = bridge.get();
            if (currentBridge != null) {
                currentBridge.close();
            }
        }
    }
}
