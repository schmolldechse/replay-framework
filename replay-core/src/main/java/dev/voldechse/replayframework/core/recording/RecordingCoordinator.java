package dev.voldechse.replayframework.core.recording;

import com.google.gson.JsonObject;
import com.google.inject.Inject;
import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
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
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import java.time.Instant;
import java.nio.file.Path;
import java.util.Map;
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
import java.util.function.Function;

/**
 * Coordinates asynchronous recording startup and session isolation.
 *
 * <p>All external work is invoked through injected asynchronous ports. The
 * coordinator never waits on a database, Paper world or checkpoint future and
 * only registers a sink while it is still in INITIALIZING, where it rejects
 * packets until the catalog transition to RECORDING succeeds.</p>
 */
final class RecordingCoordinator {
    private final ReplayAdapter adapter;
    private final CaptureRouter captureRouter;
    private final ReplayRepository replayRepository;
    private final ScopeResolver scopeResolver;
    private final RecordingTarget recordingTarget;
    private final FinalizationHandler finalizationHandler;
    private final Executor initializationExecutor;
    private final Map<RecordingSessionId, DefaultRecordingSession> sessions =
            new ConcurrentHashMap<>();
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
            Executor initializationExecutor) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.captureRouter = Objects.requireNonNull(captureRouter, "captureRouter");
        this.replayRepository = Objects.requireNonNull(replayRepository, "replayRepository");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
        this.recordingTarget = Objects.requireNonNull(recordingTarget, "recordingTarget");
        this.finalizationHandler = Objects.requireNonNull(finalizationHandler, "finalizationHandler");
        this.initializationExecutor = Objects.requireNonNull(
                initializationExecutor, "initializationExecutor");
    }

    /** Starts one isolated recording without blocking the caller. */
    CompletionStage<DefaultRecordingSession> start(RecordingRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("recording coordinator is shut down"));
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
        closed.set(true);
    }

    /** Removes a session sink idempotently from the central route. */
    void unregister(RecordingSessionId sessionId) {
        captureRouter.unregister(Objects.requireNonNull(sessionId, "sessionId"));
    }

    CompletionStage<RecordingSession> requestFinalization(
            DefaultRecordingSession session,
            ReplayCompletionReason reason) {
        try {
            CompletionStage<RecordingSession> delegated = Objects.requireNonNull(
                    finalizationHandler.finalize(session, reason),
                    "finalizationHandler result");
            CompletableFuture<RecordingSession> result = new CompletableFuture<>();
            delegated.whenComplete((completed, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(failure);
                    session.completeFinalization(null, failure);
                    return;
                }
                if (session.status() == RecordingStatus.AVAILABLE
                        || session.status() == RecordingStatus.FAILED) {
                    sessions.remove(session.id(), session);
                }
                RecordingSession finalSession = completed == null ? session : completed;
                result.complete(finalSession);
                session.completeFinalization(finalSession, null);
            });
            return result;
        } catch (Throwable failure) {
            session.completeFinalization(null, failure);
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void initializeAfterCreate(
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
            encodeInitialCheckpoint(request, sessionId, replayId, resolvedScope, result);
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
            sessions.put(sessionId, session);
            // Registration precedes the database transition. INITIALIZING
            // makes the sink reject packets, so this closes the capture gap
            // without allowing data before the catalog says RECORDING.
            captureRouter.register(session);
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
                session.activate();
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
        sessions.remove(session.id(), session);
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
        sessions.remove(session.id(), session);
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
            replayRepository.transition(new ReplayRepository.ReplayTransition(
                    replayId,
                    expectedStatus,
                    RecordingStatus.FAILED,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new ReplayRepository.FailureDetails(code, description, Instant.now())),
                    Optional.empty()));
        } catch (Throwable ignored) {
            // The live session remains FAILED even if asynchronous persistence
            // cannot be started or the database port rejects this transition.
        }
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
