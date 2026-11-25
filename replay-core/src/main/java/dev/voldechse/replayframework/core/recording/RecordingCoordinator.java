package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.recording.RecordingRequest;
import dev.voldechse.replayframework.api.recording.RecordingSession;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.recording.ReplayCompletionReason;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.api.replay.ReplayStorageBackend;
import dev.voldechse.replayframework.core.capture.CaptureRouter;
import dev.voldechse.replayframework.core.diagnostics.ReplayDiagnostics;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import java.time.Instant;
import java.nio.file.Path;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Owns the active recordings: their registry, the lifecycle of the
 * coordinator, finalization and the failures that end a recording.
 *
 * <p>A start runs as a {@link RecordingInitialization}; the scene watches
 * are kept by {@link SceneWatches} and what follows a failure outside the
 * session by {@link RecordingFailures}. All external work is invoked through
 * injected asynchronous ports. The coordinator never waits on a database,
 * Paper world or checkpoint future and only registers a sink while it is
 * still in INITIALIZING, where it rejects packets until the catalog
 * transition to RECORDING succeeds.</p>
 */
public final class RecordingCoordinator {
    private static final System.Logger LOGGER =
            System.getLogger(RecordingCoordinator.class.getName());

    private final CaptureRouter captureRouter;
    private final FinalizationHandler finalizationHandler;
    private final Consumer<ReplayEventPublisher.ReplayEvent> eventSink;
    private final ReplayDiagnostics diagnostics;
    private final SceneWatches sceneWatches;
    private final RecordingFailures failures;
    private final RecordingInitialization.Services initializationServices;
    private final Map<RecordingSessionId, DefaultRecordingSession> sessions =
            new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean adapterFailed = new AtomicBoolean();

    RecordingCoordinator(
            ReplayAdapter adapter,
            CaptureRouter captureRouter,
            ReplayRepository replayRepository,
            ScopeResolver scopeResolver,
            RecordingTarget recordingTarget,
            FinalizationHandler finalizationHandler,
            RecordingLeaseManager leaseManager,
            Executor initializationExecutor,
            Consumer<ReplayEventPublisher.ReplayEvent> eventSink,
            ReplayDiagnostics diagnostics) {
        this.captureRouter = Objects.requireNonNull(captureRouter, "captureRouter");
        this.finalizationHandler = Objects.requireNonNull(finalizationHandler, "finalizationHandler");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.sceneWatches = new SceneWatches(adapter);
        this.failures = new RecordingFailures(
                replayRepository, leaseManager, initializationExecutor, this::publishEvent);
        this.initializationServices = new RecordingInitialization.Services(
                adapter,
                replayRepository,
                scopeResolver,
                recordingTarget,
                leaseManager,
                initializationExecutor,
                failures,
                sceneWatches);
    }

    /** Starts one isolated recording without blocking the caller. */
    CompletionStage<DefaultRecordingSession> start(RecordingRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("recording coordinator is shut down"));
            }
        }
        return new RecordingInitialization(
                this, initializationServices, request, RecordingSessionId.random(), ReplayId.random())
                .start();
    }

    Optional<DefaultRecordingSession> active(RecordingSessionId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(sessions.get(id));
    }

    /** Updates internal diagnostics without exposing the collector publicly. */
    void sessionUpdated(DefaultRecordingSession session) {
        try {
            diagnostics.recordingUpdated(session, session.queue().queuedBytes());
        } catch (RuntimeException ignored) {
            // Diagnostics are passive and cannot affect capture or finalization.
        }
    }

    /** Records one successfully admitted packet. */
    void packetCaptured() {
        try {
            diagnostics.packetCaptured(1L);
        } catch (RuntimeException ignored) {
            // Diagnostics are passive and cannot affect capture or finalization.
        }
    }

    /** Converts a global capture-bridge failure into failures for active sessions. */
    void onAdapterFailure(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        adapterFailed.set(true);
        // The catalog only stores the exception class; the console is the one
        // place where the full cause of a capture-integrity failure is visible.
        LOGGER.log(System.Logger.Level.ERROR,
                "Replay capture adapter failed; active recordings fail and new ones are rejected",
                failure);
        for (DefaultRecordingSession session : sessions.values()) {
            failSession(session, session.status(), ReplayFailureCode.ADAPTER_ERROR,
                    "adapter capture failure", failure);
        }
    }

    /** Isolates one failing sink from all overlapping sessions. */
    void onSinkFailure(RecordingSessionId sessionId, Throwable failure) {
        DefaultRecordingSession session = sessions.get(Objects.requireNonNull(sessionId, "sessionId"));
        if (session == null) {
            return;
        }
        Objects.requireNonNull(failure, "failure");
        ReplayFailureCode code = isQueueOverflow(failure)
                ? ReplayFailureCode.QUEUE_OVERFLOW
                : ReplayFailureCode.INTERNAL_ERROR;
        failSession(session, RecordingStatus.RECORDING, code, "recording sink failure", failure);
    }

    /** Stops admitting new starts and leaves existing finalizer callbacks valid. */
    void shutdown() {
        synchronized (lifecycleLock) {
            closed.set(true);
        }
    }

    /**
     * Stops admission and requests clean server-shutdown finalization for all
     * sessions that have reached the recording pipeline.
     */
    CompletionStage<Void> shutdownForServer() {
        List<DefaultRecordingSession> activeSessions;
        synchronized (lifecycleLock) {
            closed.set(true);
            activeSessions = List.copyOf(sessions.values());
        }
        List<CompletionStage<Void>> stages = new ArrayList<>();
        for (DefaultRecordingSession session : activeSessions) {
            RecordingStatus status = session.status();
            if (status == RecordingStatus.RECORDING) {
                stages.add(session.requestBudgetStop(ReplayCompletionReason.SERVER_SHUTDOWN)
                        .thenApply(ignored -> null));
            } else if (status == RecordingStatus.FINALIZING) {
                stages.add(session.finalizationStage().thenApply(ignored -> null));
            } else if (status == RecordingStatus.INITIALIZING) {
                if (session.markShutdownFailed()) {
                    retire(session);
                    stages.add(failures.persistStage(
                            session.replayId(),
                            ReplayFailureCode.SERVER_CRASH,
                            "recording initialization interrupted by server shutdown"));
                }
            }
        }
        return allOf(stages);
    }

    /** Fails every non-terminal recording still owned by this coordinator. */
    CompletionStage<Void> failOutstandingForShutdownTimeout() {
        List<DefaultRecordingSession> activeSessions;
        synchronized (lifecycleLock) {
            closed.set(true);
            activeSessions = List.copyOf(sessions.values());
        }
        List<CompletionStage<Void>> stages = new ArrayList<>();
        for (DefaultRecordingSession session : activeSessions) {
            RecordingStatus expected = session.status();
            if (expected == RecordingStatus.AVAILABLE
                    || expected == RecordingStatus.FAILED
                    || expected == RecordingStatus.DELETING) {
                continue;
            }
            if (!session.markShutdownFailed()) {
                continue;
            }
            retire(session);
            failures.releaseLease(session.replayId());
            session.completeFinalization(null,
                    new IllegalStateException("recording finalization exceeded shutdown timeout"));
            stages.add(failures.persistStage(
                    session.replayId(),
                    ReplayFailureCode.SERVER_CRASH,
                    "recording finalization exceeded shutdown timeout"));
        }
        return allOf(stages);
    }

    /** Removes a session sink idempotently from the central route. */
    void unregister(RecordingSessionId sessionId) {
        captureRouter.unregister(Objects.requireNonNull(sessionId, "sessionId"));
        sceneWatches.close(sessionId);
    }

    CompletionStage<RecordingSession> requestFinalization(
            DefaultRecordingSession session,
            ReplayCompletionReason reason) {
        RecordingStatus previousStatus = session.status();
        try {
            CompletionStage<RecordingSession> delegated = Objects.requireNonNull(
                    finalizationHandler.finalize(session, reason),
                    "finalizationHandler result");
            CompletableFuture<RecordingSession> result = new CompletableFuture<>();
            delegated.whenComplete((completed, failure) -> {
                if (failure != null) {
                    sessionUpdated(session);
                    result.completeExceptionally(failure);
                    session.completeFinalization(null, failure);
                    return;
                }
                if (session.status() == RecordingStatus.AVAILABLE
                        || session.status() == RecordingStatus.FAILED) {
                    retire(session);
                }
                sessionUpdated(session);
                RecordingSession finalSession = completed == null ? session : completed;
                RecordingStatus finalStatus = session.status();
                if (previousStatus != finalStatus) {
                    publishEvent(new ReplayEventPublisher.RecordingStatusChanged(
                            session.id(),
                            session.replayId(),
                            previousStatus,
                            finalStatus,
                            Instant.now()));
                }
                if (finalStatus == RecordingStatus.AVAILABLE) {
                    publishEvent(new ReplayEventPublisher.RecordingCompleted(
                            session.id(),
                            session.replayId(),
                            finalStatus,
                            Optional.of(reason),
                            Optional.empty(),
                            Instant.now()));
                }
                result.complete(finalSession);
                session.completeFinalization(finalSession, null);
            });
            return result;
        } catch (Throwable failure) {
            session.completeFinalization(null, failure);
            sessionUpdated(session);
            return CompletableFuture.failedFuture(failure);
        }
    }

    boolean isClosed() {
        return closed.get();
    }

    boolean isAdapterFailed() {
        return adapterFailed.get();
    }

    /**
     * Registers a new session and its capture sink. Registration precedes
     * the catalog transition to RECORDING: an INITIALIZING session rejects
     * packets, so this closes the capture gap without admitting data before
     * the catalog says RECORDING.
     *
     * @throws IllegalStateException when the coordinator is shutting down
     */
    void register(DefaultRecordingSession session) {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("recording runtime is stopping");
            }
            sessions.put(session.id(), session);
            captureRouter.register(session);
            diagnostics.recordingStarted(session);
        }
    }

    /** Drops a session that never started from the registry. */
    void forget(RecordingSessionId sessionId) {
        sessions.remove(sessionId);
    }

    /** Removes a finished session from the registry and the diagnostics. */
    void retire(DefaultRecordingSession session) {
        sessions.remove(session.id(), session);
        diagnostics.recordingFinished(session.id());
    }

    void onAppenderSealed(
            DefaultRecordingSession session,
            RecordingSegmentAppender.RecordingArtifacts artifacts) {
        try {
            if (session.status() == RecordingStatus.FAILED
                    || session.status() == RecordingStatus.AVAILABLE) {
                return;
            }
            session.attachSealedArtifacts(artifacts);
            ReplayCompletionReason reason = session.requestedCompletionReason()
                    .or(() -> artifacts.completionReason())
                    .orElse(ReplayCompletionReason.MANUAL);
            requestFinalization(session, reason);
        } catch (Throwable failure) {
            onAppenderFailure(session, ReplayFailureCode.CORRUPT_DATA, failure);
        }
    }

    void onAppenderFailure(
            DefaultRecordingSession session,
            ReplayFailureCode code,
            Throwable cause) {
        RecordingStatus expected = session.status();
        String description = RecordingFailures.describe(
                session.replayId(), session.id(), "recording writer", cause);
        if (!session.markWriterFailed(code, description)) {
            return;
        }
        LOGGER.log(System.Logger.Level.ERROR, description + ", code=" + code, cause);
        failures.publishFailure(session, expected, code);
        retire(session);
        failures.releaseLease(session.replayId());
        session.completeFinalization(null, cause);
        failures.persistLater(session.replayId(), code, description);
    }

    private void failSession(
            DefaultRecordingSession session,
            RecordingStatus expectedStatus,
            ReplayFailureCode code,
            String stage,
            Throwable cause) {
        String description = RecordingFailures.describe(session.replayId(), session.id(), stage, cause);
        if (!session.markFailed(code, description)) {
            return;
        }
        LOGGER.log(System.Logger.Level.ERROR, description + ", code=" + code, cause);
        failures.publishFailure(session, expectedStatus, code);
        retire(session);
        failures.releaseLease(session.replayId());
        failures.persistLater(session.replayId(), code, description);
    }

    private static CompletionStage<Void> allOf(List<? extends CompletionStage<Void>> stages) {
        if (stages.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?>[] futures = stages.stream()
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    void onLeaseLost(ReplayId replayId, Throwable cause) {
        for (DefaultRecordingSession session : sessions.values()) {
            if (replayId.equals(session.replayId())) {
                failSession(
                        session,
                        session.status(),
                        ReplayFailureCode.DATABASE_ERROR,
                        "recording lease lost",
                        cause);
                return;
            }
        }
    }

    private static boolean isQueueOverflow(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SessionPacketQueue.QueueOverflowException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    void publishEvent(ReplayEventPublisher.ReplayEvent event) {
        try {
            eventSink.accept(event);
        } catch (RuntimeException ignored) {
            // Event observers are diagnostic consumers and cannot change recording state.
        }
    }

    /** Runtime port that resolves Paper worlds/chunks into a core snapshot. */
    @FunctionalInterface
    interface ScopeResolver {
        CompletionStage<ResolvedRecordingScope> resolve(RecordingRequest request);
    }

    /** Opaque storage selection supplied by runtime composition. */
    record RecordingTarget(
            ReplayStorageBackend storageBackend,
            Function<ReplayId, String> storageKeyFactory,
            Function<ReplayId, Path> stagingDirectoryFactory) {
        RecordingTarget {
            Objects.requireNonNull(storageBackend, "storageBackend");
            Objects.requireNonNull(storageKeyFactory, "storageKeyFactory");
            Objects.requireNonNull(stagingDirectoryFactory, "stagingDirectoryFactory");
        }
    }

    /** Finalization boundary; the coordinator delegates to it exactly once. */
    @FunctionalInterface
    interface FinalizationHandler {
        CompletionStage<RecordingSession> finalize(
                DefaultRecordingSession session,
                ReplayCompletionReason reason);
    }
}
