package dev.voldechse.replayframework.core.recording;

import com.google.gson.JsonObject;
import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.recording.RecordingRequest;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.core.port.LeaseRepository;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * The start of one recording, in steps that each wait for the asynchronous
 * port before them: create the catalog row, take the lease, verify the
 * adapter and resolve the scope, encode the initial checkpoint, register the
 * session, move the catalog to RECORDING, activate the session and start
 * its writer. A failed step fails the start and the catalog row.
 *
 * <p>No step blocks; each one continues on the initialization executor.</p>
 */
final class RecordingInitialization {
    private final RecordingCoordinator coordinator;
    private final Services services;
    private final RecordingRequest request;
    private final RecordingSessionId sessionId;
    private final ReplayId replayId;
    private final CompletableFuture<DefaultRecordingSession> result = new CompletableFuture<>();

    RecordingInitialization(
            RecordingCoordinator coordinator,
            Services services,
            RecordingRequest request,
            RecordingSessionId sessionId,
            ReplayId replayId) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.services = Objects.requireNonNull(services, "services");
        this.request = Objects.requireNonNull(request, "request");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.replayId = Objects.requireNonNull(replayId, "replayId");
    }

    /** Starts the steps; the stage completes with the active session. */
    CompletionStage<DefaultRecordingSession> start() {
        final ReplayRepository.ReplayCreate create;
        try {
            create = replayCreate();
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        Executor executor = services.executor();
        try {
            executor.execute(() -> {
                try {
                    services.replayRepository().create(create).whenCompleteAsync((ignored, failure) -> {
                        if (failure != null) {
                            result.completeExceptionally(unwrap(failure));
                            return;
                        }
                        acquireLease();
                    }, executor);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private ReplayRepository.ReplayCreate replayCreate() {
        AdapterDescriptor descriptor = Objects.requireNonNull(
                services.adapter().descriptor(), "adapter.descriptor");
        RecordingCoordinator.RecordingTarget target = services.recordingTarget();
        String storageKey = requireStorageKey(target.storageKeyFactory().apply(replayId));
        return new ReplayRepository.ReplayCreate(
                replayId,
                request.title(),
                request.description(),
                descriptor.adapterId(),
                descriptor.protocolVersion(),
                descriptor.adapterFormatRevision(),
                target.storageBackend(),
                storageKey,
                Instant.now(),
                new JsonObject());
    }

    private void acquireLease() {
        if (coordinator.isClosed()) {
            fail(ReplayFailureCode.SERVER_CRASH,
                    "server shutdown", new IllegalStateException("recording runtime is stopping"));
            return;
        }
        final CompletionStage<LeaseRepository.LeaseRow> leaseStage;
        try {
            leaseStage = Objects.requireNonNull(
                    services.leaseManager().acquire(replayId, coordinator::onLeaseLost),
                    "lease manager acquire result");
        } catch (Throwable failure) {
            fail(ReplayFailureCode.DATABASE_ERROR, "recording lease", failure);
            return;
        }
        leaseStage.whenCompleteAsync((ignored, leaseFailure) -> {
            if (leaseFailure != null) {
                fail(ReplayFailureCode.DATABASE_ERROR, "recording lease", unwrap(leaseFailure));
                return;
            }
            resolveScope();
        }, services.executor());
    }

    private void resolveScope() {
        if (coordinator.isAdapterFailed()) {
            fail(ReplayFailureCode.ADAPTER_ERROR,
                    "adapter capture failure", new IllegalStateException("adapter is failed"));
            return;
        }
        try {
            services.adapter().verifyDescriptorAndRegistry();
        } catch (Throwable failure) {
            fail(ReplayFailureCode.INCOMPATIBLE_ADAPTER, "adapter verification", failure);
            return;
        }

        final CompletionStage<ResolvedRecordingScope> scopeStage;
        try {
            scopeStage = Objects.requireNonNull(
                    services.scopeResolver().resolve(request),
                    "scopeResolver result");
        } catch (Throwable failure) {
            fail(ReplayFailureCode.INTERNAL_ERROR, "scope resolution", failure);
            return;
        }

        scopeStage.whenCompleteAsync((resolvedScope, scopeFailure) -> {
            if (scopeFailure != null) {
                fail(ReplayFailureCode.INTERNAL_ERROR, "scope resolution", unwrap(scopeFailure));
                return;
            }
            if (resolvedScope == null) {
                fail(ReplayFailureCode.INTERNAL_ERROR,
                        "scope resolution", new IllegalStateException("scope resolver returned null"));
                return;
            }
            encodeInitialCheckpoint(resolvedScope.withCapturePolicy(request.capturePolicy()));
        }, services.executor());
    }

    private void encodeInitialCheckpoint(ResolvedRecordingScope resolvedScope) {
        final CompletionStage<ReplayCheckpoint> checkpointStage;
        try {
            checkpointStage = Objects.requireNonNull(
                    services.adapter().checkpointEncoder().encode(new CheckpointEncoder.CheckpointRequest(
                            services.sceneWatches().checkpointScope(sessionId, request),
                            0L,
                            resolvedScope.initialServerTick(),
                            CheckpointEncoder.CheckpointKind.INITIAL)),
                    "checkpointEncoder result");
        } catch (Throwable failure) {
            fail(ReplayFailureCode.ADAPTER_ERROR, "initial checkpoint", failure);
            return;
        }

        checkpointStage.whenCompleteAsync((checkpoint, checkpointFailure) -> {
            if (checkpointFailure != null) {
                fail(ReplayFailureCode.ADAPTER_ERROR, "initial checkpoint", unwrap(checkpointFailure));
                return;
            }
            if (checkpoint == null) {
                fail(ReplayFailureCode.ADAPTER_ERROR,
                        "initial checkpoint", new IllegalStateException("checkpoint encoder returned null"));
                return;
            }
            register(resolvedScope, checkpoint);
        }, services.executor());
    }

    private void register(ResolvedRecordingScope resolvedScope, ReplayCheckpoint checkpoint) {
        if (coordinator.isClosed()) {
            fail(ReplayFailureCode.SERVER_CRASH,
                    "server shutdown", new IllegalStateException("recording runtime is stopping"));
            return;
        }
        if (coordinator.isAdapterFailed()) {
            fail(ReplayFailureCode.ADAPTER_ERROR,
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
                    coordinator);
            coordinator.register(session);
        } catch (Throwable failure) {
            coordinator.forget(sessionId);
            fail(ReplayFailureCode.INTERNAL_ERROR, "capture sink registration", failure);
            return;
        }
        transitionToRecording(session);
    }

    private void transitionToRecording(DefaultRecordingSession session) {
        RecordingFailures failures = services.failures();
        final CompletionStage<ReplayRepository.ReplayRow> transitionStage;
        try {
            transitionStage = Objects.requireNonNull(
                    services.replayRepository().transition(new ReplayRepository.ReplayTransition(
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
            coordinator.forget(sessionId);
            session.markFailed(ReplayFailureCode.DATABASE_ERROR,
                    RecordingFailures.describe(replayId, sessionId, "recording transition", failure));
            failures.initializationFailed(replayId, ReplayFailureCode.DATABASE_ERROR,
                    "recording transition", failure);
            result.completeExceptionally(failure);
            return;
        }

        transitionStage.whenCompleteAsync((ignored, transitionFailure) -> {
            if (transitionFailure != null) {
                coordinator.forget(sessionId);
                session.markFailed(ReplayFailureCode.DATABASE_ERROR,
                        RecordingFailures.describe(replayId, sessionId, "recording transition", transitionFailure));
                failures.activationFailed(replayId, ReplayFailureCode.DATABASE_ERROR,
                        "recording transition", unwrap(transitionFailure));
                result.completeExceptionally(unwrap(transitionFailure));
                return;
            }
            activate(session);
        }, services.executor());
    }

    private void activate(DefaultRecordingSession session) {
        try {
            if (coordinator.isClosed()) {
                coordinator.retire(session);
                session.markShutdownFailed();
                services.failures().persistStage(
                        replayId,
                        ReplayFailureCode.SERVER_CRASH,
                        "recording activation interrupted by server shutdown");
                result.completeExceptionally(
                        new IllegalStateException("recording runtime is stopping"));
                return;
            }
            session.activate();
            // Opened only now: a session rejects packets until it is
            // active, and what an observer sends first must not be lost.
            services.sceneWatches().open(session, request);
            coordinator.publishEvent(new ReplayEventPublisher.RecordingStatusChanged(
                    session.id(),
                    session.replayId(),
                    RecordingStatus.INITIALIZING,
                    RecordingStatus.RECORDING,
                    Instant.now()));
            startWriter(session);
            result.complete(session);
        } catch (Throwable activationFailure) {
            services.sceneWatches().close(sessionId);
            coordinator.forget(sessionId);
            session.markFailed(ReplayFailureCode.INTERNAL_ERROR,
                    RecordingFailures.describe(replayId, sessionId, "session activation", activationFailure));
            services.failures().activationFailed(replayId, ReplayFailureCode.INTERNAL_ERROR,
                    "session activation", activationFailure);
            result.completeExceptionally(activationFailure);
        }
    }

    /** Attaches and starts the session's writer, with its checkpoint schedule. */
    private void startWriter(DefaultRecordingSession session) {
        ReplayAdapter adapter = services.adapter();
        Path workspace = Objects.requireNonNull(
                services.recordingTarget().stagingDirectoryFactory().apply(session.replayId()),
                "stagingDirectoryFactory returned null");
        CheckpointScheduler scheduler = new CheckpointScheduler(
                request.options().checkpointInterval(),
                adapter.checkpointEncoder(),
                request.scope(),
                (elapsedNanos, serverTick, kind) -> new CheckpointEncoder.CheckpointRequest(
                        services.sceneWatches().checkpointScope(session.id(), request),
                        elapsedNanos,
                        serverTick,
                        kind,
                        session.recordingStartCaptureTimeNanos()));
        RecordingSegmentAppender appender = new RecordingSegmentAppender(
                workspace,
                adapter.descriptor().adapterId(),
                session.recordingStartCaptureTimeNanos(),
                session.queue(),
                session.initialCheckpoint(),
                request.budget(),
                scheduler,
                session::requestBudgetStop,
                (code, cause) -> coordinator.onAppenderFailure(session, code, cause),
                artifacts -> coordinator.onAppenderSealed(session, artifacts));
        session.attachAppender(appender);
        session.startAppender();
    }

    /** Fails the start before a session is registered. */
    private void fail(ReplayFailureCode code, String stage, Throwable cause) {
        services.failures().initializationFailed(replayId, code, stage, cause);
        result.completeExceptionally(cause);
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

    /**
     * What every start uses.
     *
     * @param executor runs each step after the port before it completed
     */
    record Services(
            ReplayAdapter adapter,
            ReplayRepository replayRepository,
            RecordingCoordinator.ScopeResolver scopeResolver,
            RecordingCoordinator.RecordingTarget recordingTarget,
            RecordingLeaseManager leaseManager,
            Executor executor,
            RecordingFailures failures,
            SceneWatches sceneWatches) {
        Services {
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(replayRepository, "replayRepository");
            Objects.requireNonNull(scopeResolver, "scopeResolver");
            Objects.requireNonNull(recordingTarget, "recordingTarget");
            Objects.requireNonNull(leaseManager, "leaseManager");
            Objects.requireNonNull(executor, "executor");
            Objects.requireNonNull(failures, "failures");
            Objects.requireNonNull(sceneWatches, "sceneWatches");
        }
    }
}
