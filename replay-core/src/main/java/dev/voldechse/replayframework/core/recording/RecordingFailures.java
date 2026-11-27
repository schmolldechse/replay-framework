package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * What follows a failed recording outside its session: the failure events,
 * the release of its lease and the catalog transition to FAILED. All of it
 * is best effort; the live session is already failed when it runs.
 */
final class RecordingFailures {
    private final ReplayRepository replayRepository;
    private final RecordingLeaseManager leaseManager;
    private final Executor executor;
    private final Consumer<ReplayEventPublisher.ReplayEvent> events;

    /**
     * @param executor runs the asynchronous catalog transitions
     * @param events publishes an event; it must not throw
     */
    RecordingFailures(
            ReplayRepository replayRepository,
            RecordingLeaseManager leaseManager,
            Executor executor,
            Consumer<ReplayEventPublisher.ReplayEvent> events) {
        this.replayRepository = Objects.requireNonNull(replayRepository, "replayRepository");
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** Publishes that a session changed from a status to FAILED and completed with a code. */
    void publishFailure(
            DefaultRecordingSession session,
            RecordingStatus previous,
            ReplayFailureCode code) {
        events.accept(new ReplayEventPublisher.RecordingStatusChanged(
                session.id(),
                session.replayId(),
                previous,
                RecordingStatus.FAILED,
                Instant.now()));
        events.accept(new ReplayEventPublisher.RecordingCompleted(
                session.id(),
                session.replayId(),
                RecordingStatus.FAILED,
                Optional.empty(),
                Optional.of(code),
                Instant.now()));
    }

    /** Releases the lease of a replay. */
    void releaseLease(ReplayId replayId) {
        try {
            leaseManager.release(replayId).whenComplete((ignored, failure) -> {
                // Failure persistence is handled by the owning terminal path.
            });
        } catch (Throwable ignored) {
            // Lease release is best effort after a recording failure.
        }
    }

    /**
     * Moves a replay that is still being recorded to FAILED, on the
     * executor. A replay that has already reached another status is left as
     * it is.
     */
    void persistLater(ReplayId replayId, ReplayFailureCode code, String description) {
        try {
            executor.execute(() -> persist(replayId, code, description));
        } catch (Throwable ignored) {
            // The session is already failed locally. A rejected diagnostic
            // callback must not re-enter CaptureRouter or throw into Netty.
        }
    }

    private void persist(ReplayId replayId, ReplayFailureCode code, String description) {
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
                    replayRepository.transition(failedTransition(replayId, currentStatus, code, description));
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

    /**
     * Moves a replay that has not reached a terminal status to FAILED and
     * completes when the catalog has done so.
     */
    CompletionStage<Void> persistStage(
            ReplayId replayId,
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
                    return replayRepository.transition(
                                    failedTransition(replayId, currentStatus, code, description))
                            .thenApply(ignored -> null);
                } catch (ReplayRepository.StatusTransitionConflictException conflict) {
                    return CompletableFuture.completedFuture(null);
                }
            });
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Releases the lease and moves a replay that failed while INITIALIZING to FAILED. */
    void initializationFailed(ReplayId replayId, ReplayFailureCode code, String stage, Throwable cause) {
        transitionAfterFailure(replayId, RecordingStatus.INITIALIZING, code, stage, cause);
    }

    /** Releases the lease and moves a replay that failed while becoming RECORDING to FAILED. */
    void activationFailed(ReplayId replayId, ReplayFailureCode code, String stage, Throwable cause) {
        transitionAfterFailure(replayId, RecordingStatus.RECORDING, code, stage, cause);
    }

    private void transitionAfterFailure(
            ReplayId replayId,
            RecordingStatus from,
            ReplayFailureCode code,
            String stage,
            Throwable cause) {
        releaseLease(replayId);
        try {
            replayRepository.transition(
                    failedTransition(replayId, from, code, describe(replayId, null, stage, cause)));
        } catch (Throwable ignored) {
            // Failure persistence is best effort; the local failure state is
            // authoritative and the start stage has already failed.
        }
    }

    private static ReplayRepository.ReplayTransition failedTransition(
            ReplayId replayId,
            RecordingStatus from,
            ReplayFailureCode code,
            String description) {
        return new ReplayRepository.ReplayTransition(
                replayId,
                from,
                RecordingStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new ReplayRepository.FailureDetails(code, description, Instant.now())),
                Optional.empty());
    }

    /**
     * The catalog diagnostic of a failure.
     *
     * @param sessionId the session, or {@code null} before it exists
     */
    static String describe(
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
}
