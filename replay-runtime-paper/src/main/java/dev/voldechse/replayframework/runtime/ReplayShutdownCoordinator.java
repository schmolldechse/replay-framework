package dev.voldechse.replayframework.runtime;

import dev.voldechse.replayframework.core.diagnostics.ReplayDiagnosticsSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Coordinates the bounded, idempotent shutdown of one Paper runtime graph. */
public final class ReplayShutdownCoordinator {
    private final Duration timeout;
    private final ScheduledExecutorService scheduler;
    private final ShutdownActions actions;
    private final Supplier<ReplayDiagnosticsSnapshot> diagnostics;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicReference<CompletableFuture<ShutdownResult>> shutdownFuture =
            new AtomicReference<>();
    private final AtomicBoolean sharedResourcesClosed = new AtomicBoolean();
    private final AtomicBoolean timeoutCleanupStarted = new AtomicBoolean();
    private volatile int observedRecordings;
    private volatile int observedPlaybacks;
    private final long createdNanos = System.nanoTime();

    public ReplayShutdownCoordinator(
            Duration timeout,
            ScheduledExecutorService scheduler,
            ShutdownActions actions,
            Supplier<ReplayDiagnosticsSnapshot> diagnostics) {
        this.timeout = requirePositive(timeout, "timeout");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** Starts shutdown once and returns the shared completion stage. */
    public CompletionStage<ShutdownResult> shutdown() {
        CompletableFuture<ShutdownResult> existing = shutdownFuture.get();
        if (existing != null) {
            return existing;
        }

        CompletableFuture<ShutdownResult> created = new CompletableFuture<>();
        if (!shutdownFuture.compareAndSet(null, created)) {
            return shutdownFuture.get();
        }
        state.compareAndSet(State.NEW, State.RUNNING);
        try {
            scheduler.execute(this::runShutdown);
        } catch (RuntimeException schedulingFailure) {
            runShutdown();
        }
        return created;
    }

    /**
     * Waits only for the supplied lifecycle-barrier duration. The runtime work
     * itself remains asynchronous on the configured executors.
     */
    public ShutdownResult await(Duration barrierTimeout) {
        Duration bounded = requirePositive(barrierTimeout, "barrierTimeout");
        CompletableFuture<ShutdownResult> future = shutdown().toCompletableFuture();
        try {
            return future.get(bounded.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeoutFailure) {
            triggerTimeout();
            return result(false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            triggerTimeout();
            return result(false);
        } catch (Exception failure) {
            triggerTimeout();
            return result(false);
        }
    }

    private void runShutdown() {
        state.compareAndSet(State.RUNNING, State.STOPPING);
        long startedNanos = System.nanoTime();
        ReplayDiagnosticsSnapshot before = snapshot();
        observedRecordings = before.recordings().size();
        observedPlaybacks = before.playbacks().size();
        CompletionStage<Void> recording = invoke(actions::beginRecordingShutdown);
        CompletionStage<Void> playback = invoke(actions::closePlaybacks);
        CompletableFuture<Void> phases = CompletableFuture.allOf(
                recording.toCompletableFuture(), playback.toCompletableFuture());
        ScheduledFuture<?> timeoutTask;
        try {
            timeoutTask = scheduler.schedule(this::triggerTimeout,
                    timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RuntimeException schedulingFailure) {
            timeoutTask = null;
        }
        ScheduledFuture<?> scheduledTimeout = timeoutTask;
        phases.whenComplete((ignored, failure) -> {
            if (scheduledTimeout != null) {
                scheduledTimeout.cancel(false);
            }
            if (state.compareAndSet(State.STOPPING, State.TERMINATED)) {
                finishNormally(startedNanos);
            }
        });
    }

    private void triggerTimeout() {
        if (!state.compareAndSet(State.STOPPING, State.TIMED_OUT)) {
            return;
        }
        CompletionStage<Void> failureStage = invoke(actions::failOutstandingRecordings);
        failureStage.whenComplete((ignored, failure) -> finishAfterTimeout());
        try {
            scheduler.schedule(this::finishAfterTimeout,
                    timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // The completion callback remains the primary cleanup path.
        }
    }

    private void finishNormally(long startedNanos) {
        CompletionStage<Void> leases = invoke(actions::releaseLeases);
        leases.whenComplete((ignored, leaseFailure) -> {
            closeSharedResources();
            complete(new ShutdownResult(
                    true,
                    0,
                    observedPlaybacks,
                    elapsedSince(startedNanos),
                    snapshot()));
        });
    }

    private void finishAfterTimeout() {
        if (!timeoutCleanupStarted.compareAndSet(false, true)) {
            return;
        }
        CompletionStage<Void> leases = invoke(actions::releaseLeases);
        leases.whenComplete((ignored, leaseFailure) -> {
            closeSharedResources();
            complete(new ShutdownResult(
                    false,
                    observedRecordings,
                    observedPlaybacks,
                    elapsedSince(createdNanos),
                    snapshot()));
        });
    }

    private void closeSharedResources() {
        if (sharedResourcesClosed.compareAndSet(false, true)) {
            try {
                actions.closeSharedResources();
            } catch (RuntimeException ignored) {
                // Resource cleanup is best effort; later lifecycle steps have already been isolated.
            }
        }
    }

    private void complete(ShutdownResult result) {
        CompletableFuture<ShutdownResult> future = shutdownFuture.get();
        if (future != null) {
            future.complete(result);
        }
    }

    private CompletionStage<Void> invoke(StageSupplier supplier) {
        try {
            CompletionStage<Void> stage = supplier.get();
            return stage == null ? CompletableFuture.completedFuture(null) : stage;
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private ReplayDiagnosticsSnapshot snapshot() {
        try {
            ReplayDiagnosticsSnapshot snapshot = diagnostics.get();
            return Objects.requireNonNull(snapshot, "diagnostics snapshot");
        } catch (RuntimeException ignored) {
            return new ReplayDiagnosticsSnapshot(
                    Instant.now(), List.of(), List.of(), 0L, 0L, 0L, 0L, 0L, Optional.empty());
        }
    }

    private ShutdownResult result(boolean completedWithinTimeout) {
        return new ShutdownResult(
                completedWithinTimeout,
                completedWithinTimeout ? 0 : observedRecordings,
                observedPlaybacks,
                Duration.ofNanos(Math.max(0L, System.nanoTime() - createdNanos)),
                snapshot());
    }

    private static Duration elapsedSince(long startedNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos));
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /** Internal actions keep the coordinator independent from Bukkit and Guice. */
    public interface ShutdownActions {
        CompletionStage<Void> beginRecordingShutdown();

        CompletionStage<Void> failOutstandingRecordings();

        CompletionStage<Void> closePlaybacks();

        CompletionStage<Void> releaseLeases();

        void closeSharedResources();
    }

    /** Immutable result of one coordinated shutdown attempt. */
    public record ShutdownResult(
            boolean completedWithinTimeout,
            int forcedRecordingFailures,
            int closedPlaybackSessions,
            Duration elapsed,
            ReplayDiagnosticsSnapshot diagnostics) {
        public ShutdownResult {
            if (forcedRecordingFailures < 0 || closedPlaybackSessions < 0) {
                throw new IllegalArgumentException("shutdown counters must not be negative");
            }
            Objects.requireNonNull(elapsed, "elapsed");
            if (elapsed.isNegative()) {
                throw new IllegalArgumentException("elapsed must not be negative");
            }
            Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }

    @FunctionalInterface
    private interface StageSupplier {
        CompletionStage<Void> get();
    }

    private enum State {
        NEW,
        RUNNING,
        STOPPING,
        TIMED_OUT,
        TERMINATED
    }
}
