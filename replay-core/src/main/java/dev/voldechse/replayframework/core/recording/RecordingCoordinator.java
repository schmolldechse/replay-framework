package dev.voldechse.replayframework.core.recording;

import com.google.gson.JsonObject;
import com.google.inject.Inject;
import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.recording.RecordingRequest;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.api.recording.RecordingSession;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.recording.ReplayCompletionReason;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.api.replay.ReplayStorageBackend;
import dev.voldechse.replayframework.core.capture.CaptureRouter;
import dev.voldechse.replayframework.core.diagnostics.ReplayDiagnostics;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import java.time.Instant;
import java.nio.file.Path;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Coordinates asynchronous recording startup and session isolation.
 *
 * <p>All external work is invoked through injected asynchronous ports. The
 * coordinator never waits on a database, Paper world or checkpoint future and
 * only registers a sink while it is still in INITIALIZING, where it rejects
 * packets until the catalog transition to RECORDING succeeds.</p>
 */
public final class RecordingCoordinator {
    private final ReplayAdapter adapter;
    private final CaptureRouter captureRouter;
    private final ReplayRepository replayRepository;
    private final ScopeResolver scopeResolver;
    private final RecordingTarget recordingTarget;
    private final FinalizationHandler finalizationHandler;
    private final RecordingLeaseManager leaseManager;
    private final Executor initializationExecutor;
    private final Consumer<ReplayEventPublisher.ReplayEvent> eventSink;
    private final ReplayDiagnostics diagnostics;
    private final Map<RecordingSessionId, DefaultRecordingSession> sessions =
            new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean adapterFailed = new AtomicBoolean();

    @Inject
    RecordingCoordinator(
            ReplayAdapter adapter,
            CaptureRouter captureRouter,
            ReplayRepository replayRepository,
            ScopeResolver scopeResolver,
            RecordingTarget recordingTarget,
            FinalizationHandler finalizationHandler,
            RecordingLeaseManager leaseManager,
            Executor initializationExecutor,
            ReplayDiagnostics diagnostics) {
        this(
                adapter,
                captureRouter,
                replayRepository,
                scopeResolver,
                recordingTarget,
                finalizationHandler,
                leaseManager,
                initializationExecutor,
                ignored -> {
                },
                diagnostics);
    }

    /** Compatibility constructor for isolated compositions without events. */
    RecordingCoordinator(
            ReplayAdapter adapter,
            CaptureRouter captureRouter,
            ReplayRepository replayRepository,
            ScopeResolver scopeResolver,
            RecordingTarget recordingTarget,
            FinalizationHandler finalizationHandler,
            RecordingLeaseManager leaseManager,
            Executor initializationExecutor) {
        this(
                adapter,
                captureRouter,
                replayRepository,
                scopeResolver,
                recordingTarget,
                finalizationHandler,
                leaseManager,
                initializationExecutor,
                ignored -> {
                },
                new ReplayDiagnostics());
    }

    RecordingCoordinator(
            ReplayAdapter adapter,
            CaptureRouter captureRouter,
            ReplayRepository replayRepository,
            ScopeResolver scopeResolver,
            RecordingTarget recordingTarget,
            FinalizationHandler finalizationHandler,
            RecordingLeaseManager leaseManager,
            Executor initializationExecutor,
            Consumer<ReplayEventPublisher.ReplayEvent> eventSink) {
        this(
                adapter,
                captureRouter,
                replayRepository,
                scopeResolver,
                recordingTarget,
                finalizationHandler,
                leaseManager,
                initializationExecutor,
                eventSink,
                new ReplayDiagnostics());
    }

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
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.captureRouter = Objects.requireNonNull(captureRouter, "captureRouter");
        this.replayRepository = Objects.requireNonNull(replayRepository, "replayRepository");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
        this.recordingTarget = Objects.requireNonNull(recordingTarget, "recordingTarget");
        this.finalizationHandler = Objects.requireNonNull(finalizationHandler, "finalizationHandler");
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager");
        this.initializationExecutor = Objects.requireNonNull(
                initializationExecutor, "initializationExecutor");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** Compatibility constructor for isolated pre-lease test compositions. */
    RecordingCoordinator(
            ReplayAdapter adapter,
            CaptureRouter captureRouter,
            ReplayRepository replayRepository,
            ScopeResolver scopeResolver,
            RecordingTarget recordingTarget,
            FinalizationHandler finalizationHandler,
            Executor initializationExecutor) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.captureRouter = Objects.requireNonNull(captureRouter, "captureRouter");
        this.replayRepository = Objects.requireNonNull(replayRepository, "replayRepository");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
        this.recordingTarget = Objects.requireNonNull(recordingTarget, "recordingTarget");
        this.finalizationHandler = Objects.requireNonNull(finalizationHandler, "finalizationHandler");
        this.leaseManager = null;
        this.initializationExecutor = Objects.requireNonNull(
                initializationExecutor, "initializationExecutor");
        this.eventSink = ignored -> {
        };
        this.diagnostics = new ReplayDiagnostics();
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

        RecordingSessionId sessionId = RecordingSessionId.random();
        ReplayId replayId = ReplayId.random();
        final ReplayRepository.ReplayCreate create;
        try {
            AdapterDescriptor descriptor = Objects.requireNonNull(
                    adapter.descriptor(), "adapter.descriptor");
            String storageKey = requireStorageKey(
                    recordingTarget.storageKeyFactory().apply(replayId));
            create = new ReplayRepository.ReplayCreate(
                    replayId,
                    request.title(),
                    request.description(),
                    descriptor.adapterId(),
                    descriptor.protocolVersion(),
                    descriptor.adapterFormatRevision(),
                    recordingTarget.storageBackend(),
                    storageKey,
                    Instant.now(),
                    new JsonObject());
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }

        CompletableFuture<DefaultRecordingSession> result = new CompletableFuture<>();
        try {
            initializationExecutor.execute(() -> {
                try {
                    replayRepository.create(create).whenCompleteAsync((ignored, failure) -> {
                        if (failure != null) {
                            result.completeExceptionally(unwrap(failure));
                            return;
                        }
                        initializeAfterCreate(request, sessionId, replayId, result);
                    }, initializationExecutor);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
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
                    sessions.remove(session.id(), session);
                    diagnostics.recordingFinished(session.id());
                    stages.add(persistFailureTransitionStage(
                            session.replayId(),
                            RecordingStatus.INITIALIZING,
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
            sessions.remove(session.id(), session);
            diagnostics.recordingFinished(session.id());
            releaseLeaseQuietly(session.replayId());
            session.completeFinalization(null,
                    new IllegalStateException("recording finalization exceeded shutdown timeout"));
            stages.add(persistFailureTransitionStage(
                    session.replayId(),
                    expected,
                    ReplayFailureCode.SERVER_CRASH,
                    "recording finalization exceeded shutdown timeout"));
        }
        return allOf(stages);
    }

    /** Removes a session sink idempotently from the central route. */
    void unregister(RecordingSessionId sessionId) {
        captureRouter.unregister(Objects.requireNonNull(sessionId, "sessionId"));
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
                    sessions.remove(session.id(), session);
                    diagnostics.recordingFinished(session.id());
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

    private void initializeAfterCreate(
            RecordingRequest request,
            RecordingSessionId sessionId,
            ReplayId replayId,
            CompletableFuture<DefaultRecordingSession> result) {
        if (closed.get()) {
            failInitialization(replayId, result, ReplayFailureCode.SERVER_CRASH,
                    "server shutdown", new IllegalStateException("recording runtime is stopping"));
            return;
        }
        if (leaseManager != null) {
            final CompletionStage<dev.voldechse.replayframework.core.port.LeaseRepository.LeaseRow>
                    leaseStage;
            try {
                leaseStage = Objects.requireNonNull(
                        leaseManager.acquire(replayId, this::onLeaseLost),
                        "lease manager acquire result");
            } catch (Throwable failure) {
                failInitialization(replayId, result, ReplayFailureCode.DATABASE_ERROR,
                        "recording lease", failure);
                return;
            }
            leaseStage.whenCompleteAsync((ignored, leaseFailure) -> {
                if (leaseFailure != null) {
                    failInitialization(replayId, result, ReplayFailureCode.DATABASE_ERROR,
                            "recording lease", unwrap(leaseFailure));
                    return;
                }
                initializeAfterLease(request, sessionId, replayId, result);
            }, initializationExecutor);
            return;
        }
        initializeAfterLease(request, sessionId, replayId, result);
    }

    private void initializeAfterLease(
            RecordingRequest request,
            RecordingSessionId sessionId,
            ReplayId replayId,
            CompletableFuture<DefaultRecordingSession> result) {
        if (adapterFailed.get()) {
            failInitialization(replayId, result, ReplayFailureCode.ADAPTER_ERROR,
                    "adapter capture failure", new IllegalStateException("adapter is failed"));
            return;
        }
        try {
            adapter.verifyDescriptorAndRegistry();
        } catch (Throwable failure) {
            failInitialization(replayId, result, ReplayFailureCode.INCOMPATIBLE_ADAPTER,
                    "adapter verification", failure);
            return;
        }

        final CompletionStage<ResolvedRecordingScope> scopeStage;
        try {
            scopeStage = Objects.requireNonNull(
                    scopeResolver.resolve(request.scope(), request.participants()),
                    "scopeResolver result");
        } catch (Throwable failure) {
            failInitialization(replayId, result, ReplayFailureCode.INTERNAL_ERROR,
                    "scope resolution", failure);
            return;
        }

        scopeStage.whenCompleteAsync((resolvedScope, scopeFailure) -> {
            if (scopeFailure != null) {
                failInitialization(replayId, result, ReplayFailureCode.INTERNAL_ERROR,
                        "scope resolution", unwrap(scopeFailure));
                return;
            }
            if (resolvedScope == null) {
                failInitialization(replayId, result, ReplayFailureCode.INTERNAL_ERROR,
                        "scope resolution", new IllegalStateException("scope resolver returned null"));
                return;
            }
            encodeInitialCheckpoint(
                    request,
                    sessionId,
                    replayId,
                    resolvedScope.withCapturePolicy(request.capturePolicy()),
                    result);
        }, initializationExecutor);
    }

    private void encodeInitialCheckpoint(
            RecordingRequest request,
            RecordingSessionId sessionId,
            ReplayId replayId,
            ResolvedRecordingScope resolvedScope,
            CompletableFuture<DefaultRecordingSession> result) {
        final CompletionStage<ReplayCheckpoint> checkpointStage;
        try {
            checkpointStage = Objects.requireNonNull(
                    adapter.checkpointEncoder().encode(new CheckpointEncoder.CheckpointRequest(
                            request.scope(),
                            0L,
                            resolvedScope.initialServerTick(),
                            CheckpointEncoder.CheckpointKind.INITIAL)),
                    "checkpointEncoder result");
        } catch (Throwable failure) {
            failInitialization(replayId, result, ReplayFailureCode.ADAPTER_ERROR,
                    "initial checkpoint", failure);
            return;
        }

        checkpointStage.whenCompleteAsync((checkpoint, checkpointFailure) -> {
            if (checkpointFailure != null) {
                failInitialization(replayId, result, ReplayFailureCode.ADAPTER_ERROR,
                        "initial checkpoint", unwrap(checkpointFailure));
                return;
            }
            if (checkpoint == null) {
                failInitialization(replayId, result, ReplayFailureCode.ADAPTER_ERROR,
                        "initial checkpoint", new IllegalStateException("checkpoint encoder returned null"));
                return;
            }
            registerAndActivate(
                    request, sessionId, replayId, resolvedScope, checkpoint, result);
        }, initializationExecutor);
    }

    private void registerAndActivate(
            RecordingRequest request,
            RecordingSessionId sessionId,
            ReplayId replayId,
            ResolvedRecordingScope resolvedScope,
            ReplayCheckpoint checkpoint,
            CompletableFuture<DefaultRecordingSession> result) {
        if (closed.get()) {
            failInitialization(replayId, result, ReplayFailureCode.SERVER_CRASH,
                    "server shutdown", new IllegalStateException("recording runtime is stopping"));
            return;
        }
        if (adapterFailed.get()) {
            failInitialization(replayId, result, ReplayFailureCode.ADAPTER_ERROR,
                    "adapter capture failure", new IllegalStateException("adapter is failed"));
            return;
        }
        long queueBytes = request.budget().maxQueueBytes()
                .orElse(request.budget().maxSegmentBytes());
        final DefaultRecordingSession session;
        try {
            session = new DefaultRecordingSession(
                    sessionId,
                    replayId,
                    request,
                    resolvedScope,
                    checkpoint,
                    new SessionPacketQueue(queueBytes),
                    this);
            synchronized (lifecycleLock) {
                if (closed.get()) {
                    throw new IllegalStateException("recording runtime is stopping");
                }
                sessions.put(sessionId, session);
                // Registration precedes the database transition. INITIALIZING
                // makes the sink reject packets, so this closes the capture gap
                // without allowing data before the catalog says RECORDING.
                captureRouter.register(session);
                diagnostics.recordingStarted(session);
            }
        } catch (Throwable failure) {
            sessions.remove(sessionId);
            failInitialization(replayId, result, ReplayFailureCode.INTERNAL_ERROR,
                    "capture sink registration", failure);
            return;
        }

        final CompletionStage<ReplayRepository.ReplayRow> transitionStage;
        try {
            transitionStage = Objects.requireNonNull(
                    replayRepository.transition(new ReplayRepository.ReplayTransition(
                            replayId,
                            RecordingStatus.INITIALIZING,
                            RecordingStatus.RECORDING,
                            Optional.of(Instant.now()),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty())),
                    "recording transition result");
        } catch (Throwable failure) {
            sessions.remove(sessionId);
            session.markFailed(ReplayFailureCode.DATABASE_ERROR,
                    diagnostic(replayId, sessionId, "recording transition", failure));
            failInitializationTransition(replayId, ReplayFailureCode.DATABASE_ERROR,
                    "recording transition", failure);
            result.completeExceptionally(failure);
            return;
        }

        transitionStage.whenCompleteAsync((ignored, transitionFailure) -> {
            if (transitionFailure != null) {
                sessions.remove(sessionId);
                session.markFailed(ReplayFailureCode.DATABASE_ERROR,
                        diagnostic(replayId, sessionId, "recording transition", transitionFailure));
                failActiveTransition(replayId, ReplayFailureCode.DATABASE_ERROR,
                        "recording transition", unwrap(transitionFailure));
                result.completeExceptionally(unwrap(transitionFailure));
                return;
            }
            try {
                if (closed.get()) {
                    sessions.remove(sessionId, session);
                    diagnostics.recordingFinished(session.id());
                    session.markShutdownFailed();
                    persistFailureTransitionStage(
                            replayId,
                            RecordingStatus.RECORDING,
                            ReplayFailureCode.SERVER_CRASH,
                            "recording activation interrupted by server shutdown");
                    result.completeExceptionally(
                            new IllegalStateException("recording runtime is stopping"));
                    return;
                }
                session.activate();
                publishEvent(new ReplayEventPublisher.RecordingStatusChanged(
                        session.id(),
                        session.replayId(),
                        RecordingStatus.INITIALIZING,
                        RecordingStatus.RECORDING,
                        Instant.now()));
                installAppender(request, session);
                result.complete(session);
            } catch (Throwable activationFailure) {
                sessions.remove(sessionId);
                session.markFailed(ReplayFailureCode.INTERNAL_ERROR,
                        diagnostic(replayId, sessionId, "session activation", activationFailure));
                failActiveTransition(replayId, ReplayFailureCode.INTERNAL_ERROR,
                        "session activation", activationFailure);
                result.completeExceptionally(activationFailure);
            }
        }, initializationExecutor);
    }

    private void installAppender(RecordingRequest request, DefaultRecordingSession session) {
        if (recordingTarget.stagingDirectoryFactory() == null) {
            // The compatibility target does not provide a staging workspace.
            // A production composition must supply a validated staging
            // factory; without it this coordinator deliberately does not
            // fabricate an artifact path.
            return;
        }
        Path workspace = Objects.requireNonNull(
                recordingTarget.stagingDirectoryFactory().apply(session.replayId()),
                "stagingDirectoryFactory returned null");
        CheckpointScheduler scheduler = new CheckpointScheduler(
                request.options().checkpointInterval(),
                adapter.checkpointEncoder(),
                request.scope(),
                (elapsedNanos, serverTick, kind) -> new CheckpointEncoder.CheckpointRequest(
                        request.scope(), elapsedNanos, serverTick, kind));
        RecordingSegmentAppender appender = new RecordingSegmentAppender(
                workspace,
                adapter.descriptor().adapterId(),
                session.recordingStartCaptureTimeNanos(),
                session.queue(),
                session.initialCheckpoint(),
                request.budget(),
                scheduler,
                adapter.checkpointSignals(),
                session::requestBudgetStop,
                (code, cause) -> onAppenderFailure(session, code, cause),
                artifacts -> onAppenderSealed(session, artifacts));
        session.attachAppender(appender);
        session.startAppender();
    }

    private void onAppenderSealed(
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

    private void onAppenderFailure(
            DefaultRecordingSession session,
            ReplayFailureCode code,
            Throwable cause) {
        RecordingStatus expected = session.status();
        String description = diagnostic(session.replayId(), session.id(), "recording writer", cause);
        if (!session.markWriterFailed(code, description)) {
            return;
        }
        publishFailure(session, expected, code);
        sessions.remove(session.id(), session);
        diagnostics.recordingFinished(session.id());
        releaseLeaseQuietly(session.replayId());
        session.completeFinalization(null, cause);
        try {
            initializationExecutor.execute(() -> persistFailureTransition(
                    session.replayId(), expected, code, description));
        } catch (Throwable ignored) {
            // The live session is already FAILED; persistence remains best effort.
        }
    }

    private void failSession(
            DefaultRecordingSession session,
            RecordingStatus expectedStatus,
            ReplayFailureCode code,
            String stage,
            Throwable cause) {
        String description = diagnostic(session.replayId(), session.id(), stage, cause);
        if (!session.markFailed(code, description)) {
            return;
        }
        publishFailure(session, expectedStatus, code);
        sessions.remove(session.id(), session);
        diagnostics.recordingFinished(session.id());
        releaseLeaseQuietly(session.replayId());
        try {
            initializationExecutor.execute(() -> persistFailureTransition(
                    session.replayId(), expectedStatus, code, description));
        } catch (Throwable ignored) {
            // The session is already failed locally. A rejected diagnostic
            // diagnostic callback must not re-enter CaptureRouter or throw
            // into Netty.
        }
    }

    private void persistFailureTransition(
            ReplayId replayId,
            RecordingStatus expectedStatus,
            ReplayFailureCode code,
            String description) {
        try {
            CompletionStage<Optional<ReplayRepository.ReplayRow>> currentStage =
                    Objects.requireNonNull(replayRepository.find(replayId), "replay repository find result");
            currentStage.whenComplete((current, findFailure) -> {
                if (findFailure != null || current == null || current.isEmpty()) {
                    return;
                }
                RecordingStatus currentStatus = current.orElseThrow().status();
                if (currentStatus != RecordingStatus.INITIALIZING
                        && currentStatus != RecordingStatus.RECORDING
                        && currentStatus != RecordingStatus.FINALIZING) {
                    return;
                }
                try {
                    replayRepository.transition(new ReplayRepository.ReplayTransition(
                            replayId,
                            currentStatus,
                            RecordingStatus.FAILED,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(new ReplayRepository.FailureDetails(
                                    code, description, Instant.now())),
                            Optional.empty()));
                } catch (ReplayRepository.StatusTransitionConflictException ignored) {
                    // A concurrent terminal path has already resolved the row.
                } catch (Throwable ignored) {
                    // The local session remains failed when diagnostic persistence rejects the update.
                }
            });
        } catch (Throwable ignored) {
            // The live session remains FAILED even if asynchronous persistence
            // cannot be started or the database port rejects this transition.
        }
    }

    private CompletionStage<Void> persistFailureTransitionStage(
            ReplayId replayId,
            RecordingStatus expectedStatus,
            ReplayFailureCode code,
            String description) {
        try {
            CompletionStage<Optional<ReplayRepository.ReplayRow>> currentStage =
                    Objects.requireNonNull(replayRepository.find(replayId), "replay repository find result");
            return currentStage.thenCompose(current -> {
                if (current == null || current.isEmpty()) {
                    return CompletableFuture.completedFuture(null);
                }
                RecordingStatus currentStatus = current.orElseThrow().status();
                if (currentStatus == RecordingStatus.AVAILABLE
                        || currentStatus == RecordingStatus.FAILED
                        || currentStatus == RecordingStatus.DELETING) {
                    return CompletableFuture.completedFuture(null);
                }
                try {
                    return replayRepository.transition(new ReplayRepository.ReplayTransition(
                                    replayId,
                                    currentStatus,
                                    RecordingStatus.FAILED,
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.of(new ReplayRepository.FailureDetails(
                                            code, description, Instant.now())),
                                    Optional.empty()))
                            .thenApply(ignored -> null);
                } catch (ReplayRepository.StatusTransitionConflictException conflict) {
                    return CompletableFuture.completedFuture(null);
                }
            });
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
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

    private void failInitialization(
            ReplayId replayId,
            CompletableFuture<DefaultRecordingSession> result,
            ReplayFailureCode code,
            String stage,
            Throwable cause) {
        String description = diagnostic(replayId, null, stage, cause);
        failInitializationTransition(replayId, code, stage, cause, description);
        result.completeExceptionally(cause);
    }

    private void failInitializationTransition(
            ReplayId replayId,
            ReplayFailureCode code,
            String stage,
            Throwable cause) {
        failInitializationTransition(
                replayId, code, stage, cause, diagnostic(replayId, null, stage, cause));
    }

    private void failInitializationTransition(
            ReplayId replayId,
            ReplayFailureCode code,
            String stage,
            Throwable cause,
            String description) {
        releaseLeaseQuietly(replayId);
        try {
            replayRepository.transition(new ReplayRepository.ReplayTransition(
                    replayId,
                    RecordingStatus.INITIALIZING,
                    RecordingStatus.FAILED,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new ReplayRepository.FailureDetails(code, description, Instant.now())),
                    Optional.empty()));
        } catch (Throwable ignored) {
            // Failure persistence is best effort after the original start
            // stage has already been completed exceptionally.
        }
    }

    private void failActiveTransition(
            ReplayId replayId,
            ReplayFailureCode code,
            String stage,
            Throwable cause) {
        releaseLeaseQuietly(replayId);
        try {
            replayRepository.transition(new ReplayRepository.ReplayTransition(
                    replayId,
                    RecordingStatus.RECORDING,
                    RecordingStatus.FAILED,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new ReplayRepository.FailureDetails(
                            code, diagnostic(replayId, null, stage, cause), Instant.now())),
                    Optional.empty()));
        } catch (Throwable ignored) {
            // Local failure state remains authoritative for the live session.
        }
    }

    private void onLeaseLost(ReplayId replayId, Throwable cause) {
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

    private void releaseLeaseQuietly(ReplayId replayId) {
        if (leaseManager == null) {
            return;
        }
        try {
            leaseManager.release(replayId).whenComplete((ignored, failure) -> {
                // Failure persistence is handled by the owning terminal path.
            });
        } catch (Throwable ignored) {
            // Lease release is best effort after a recording failure.
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

    private void publishFailure(
            DefaultRecordingSession session,
            RecordingStatus previous,
            ReplayFailureCode code) {
        publishEvent(new ReplayEventPublisher.RecordingStatusChanged(
                session.id(),
                session.replayId(),
                previous,
                RecordingStatus.FAILED,
                Instant.now()));
        publishEvent(new ReplayEventPublisher.RecordingCompleted(
                session.id(),
                session.replayId(),
                RecordingStatus.FAILED,
                Optional.empty(),
                Optional.of(code),
                Instant.now()));
    }

    private void publishEvent(ReplayEventPublisher.ReplayEvent event) {
        try {
            eventSink.accept(event);
        } catch (RuntimeException ignored) {
            // Event observers are diagnostic consumers and cannot change recording state.
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String requireStorageKey(String storageKey) {
        Objects.requireNonNull(storageKey, "storageKey");
        if (storageKey.isBlank()) {
            throw new IllegalArgumentException("storage key must not be blank");
        }
        return storageKey;
    }

    private static String diagnostic(
            ReplayId replayId,
            RecordingSessionId sessionId,
            String stage,
            Throwable cause) {
        StringBuilder value = new StringBuilder("recording ").append(stage)
                .append(" failed; replay=").append(replayId);
        if (sessionId != null) {
            value.append(", session=").append(sessionId);
        }
        // Only the exception class is persisted. Raw messages may contain
        // payload fragments, credentials or remote URLs and are not safe DB
        // diagnostics.
        value.append(", cause=").append(cause.getClass().getSimpleName());
        return value.toString();
    }

    /** Runtime port that resolves Paper worlds/chunks into a core snapshot. */
    @FunctionalInterface
    interface ScopeResolver {
        CompletionStage<ResolvedRecordingScope> resolve(
                RecordingScope requested,
                Set<UUID> participants);
    }

    /** Opaque storage selection supplied by runtime composition. */
    record RecordingTarget(
            ReplayStorageBackend storageBackend,
            Function<ReplayId, String> storageKeyFactory,
            Function<ReplayId, Path> stagingDirectoryFactory) {
        /** Compatibility composition without a staging workspace. */
        RecordingTarget(
                ReplayStorageBackend storageBackend,
                Function<ReplayId, String> storageKeyFactory) {
            this(storageBackend, storageKeyFactory, null);
        }

        RecordingTarget {
            Objects.requireNonNull(storageBackend, "storageBackend");
            Objects.requireNonNull(storageKeyFactory, "storageKeyFactory");
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
