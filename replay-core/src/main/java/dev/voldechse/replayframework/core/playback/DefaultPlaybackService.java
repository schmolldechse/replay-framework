package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
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
import dev.voldechse.replayframework.core.playback.cache.SegmentCache;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.format.CorruptReplayArtifactException;
import dev.voldechse.replayframework.format.CorruptReplaySegmentException;
import dev.voldechse.replayframework.format.ReplayCheckpointReader;
import dev.voldechse.replayframework.format.ReplayIndexReader;
import dev.voldechse.replayframework.format.ReplayManifest;
import dev.voldechse.replayframework.format.ReplaySegmentReader;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import org.bukkit.entity.Player;

/** Opens and manages one isolated playback graph per viewer. */
final class DefaultPlaybackService implements PlaybackService {

    private final ReplayRepository replayRepository;
    private final ReplayArtifactReader artifactReader;
    private final ReplayAdapter adapter;
    private final Executor ioExecutor;
    private final PlaybackCoordinator coordinator;
    private final PlaybackEventDispatcher eventDispatcher;
    private final ReplayDiagnostics diagnostics;
    private final SessionAssembly.Services assemblyServices;

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
            ReplayDiagnostics diagnostics,
            ObserverPlacementService observerPlacementService) {
        this.replayRepository = Objects.requireNonNull(replayRepository, "replayRepository");
        this.artifactReader = Objects.requireNonNull(artifactReader, "artifactReader");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.eventDispatcher = Objects.requireNonNull(eventDispatcher, "eventDispatcher");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.assemblyServices = new SessionAssembly.Services(
                adapter,
                new TrackOpener(
                        artifactReader, sharedSegmentCache, indexReader, checkpointReader, segmentReader, ioExecutor),
                commandExecutor,
                coordinator,
                eventDispatcher,
                diagnostics,
                observerPlacementService);
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

        SessionAssembly assembly = new SessionAssembly(
                assemblyServices,
                sessionId,
                viewerId,
                validatedRequest,
                timelineListener(sessionId, validatedRequest.replayId(), viewerId));
        CompletionStage<PlaybackSession> opening;
        try {
            opening = loadReplay(validatedRequest).thenCompose(assembly::assemble);
        } catch (RuntimeException exception) {
            coordinator.release(sessionId, viewerId);
            return CompletableFuture.failedFuture(exception);
        }

        return opening.whenComplete((ignored, failure) -> {
            if (failure != null) {
                assembly.closeAfterFailure();
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

    private static CompletionException corrupt(String message) {
        return new CompletionException(new CorruptReplayArtifactException(message));
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

}
