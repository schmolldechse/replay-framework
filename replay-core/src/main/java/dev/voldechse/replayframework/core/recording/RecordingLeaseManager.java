package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.core.port.LeaseRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the runtime identity and heartbeat handles for active recordings.
 *
 * <p>Lease callbacks are deliberately kept outside the capture path. A
 * repository failure therefore stops only the affected recording and is
 * delivered on the configured callback executor.</p>
 */
public final class RecordingLeaseManager implements AutoCloseable {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

    private final LeaseRepository repository;
    private final Clock clock;
    private final Duration leaseTtl;
    private final Duration heartbeatInterval;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Executor callbackExecutor;
    private final UUID runtimeInstanceId;
    private final ConcurrentHashMap<ReplayId, LeaseHandle> handles = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates a manager with the standard runtime lease durations. */
    public RecordingLeaseManager(
            LeaseRepository repository,
            Clock clock,
            ScheduledExecutorService heartbeatExecutor,
            Executor callbackExecutor) {
        this(
                repository,
                clock,
                DEFAULT_TTL,
                DEFAULT_HEARTBEAT_INTERVAL,
                heartbeatExecutor,
                callbackExecutor,
                UUID.randomUUID());
    }

    /** Creates a manager with explicit durations and runtime identity. */
    public RecordingLeaseManager(
            LeaseRepository repository,
            Clock clock,
            Duration leaseTtl,
            Duration heartbeatInterval,
            ScheduledExecutorService heartbeatExecutor,
            Executor callbackExecutor,
            UUID runtimeInstanceId) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseTtl = requirePositive(leaseTtl, "leaseTtl");
        this.heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
        if (!heartbeatInterval.minus(leaseTtl).isNegative()) {
            throw new IllegalArgumentException("heartbeatInterval must be shorter than leaseTtl");
        }
        this.heartbeatExecutor = Objects.requireNonNull(heartbeatExecutor, "heartbeatExecutor");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.runtimeInstanceId = Objects.requireNonNull(runtimeInstanceId, "runtimeInstanceId");
    }

    /** Acquires one lease and starts its heartbeat after the repository confirms ownership. */
    public CompletionStage<LeaseRepository.LeaseRow> acquire(
            ReplayId replayId,
            LeaseLossHandler lossHandler) {
        Objects.requireNonNull(replayId, "replayId");
        Objects.requireNonNull(lossHandler, "lossHandler");
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("recording lease manager is closed"));
        }

        LeaseHandle handle = new LeaseHandle(replayId, lossHandler);
        if (handles.putIfAbsent(replayId, handle) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("recording lease is already acquired locally: " + replayId));
        }

        final Instant acquiredAt;
        final Instant expiresAt;
        try {
            acquiredAt = clock.instant();
            expiresAt = acquiredAt.plus(leaseTtl);
        } catch (RuntimeException failure) {
            handles.remove(replayId, handle);
            return CompletableFuture.failedFuture(failure);
        }

        final CompletionStage<LeaseRepository.LeaseRow> acquireStage;
        try {
            acquireStage = Objects.requireNonNull(
                    repository.acquire(new LeaseRepository.LeaseAcquire(
                            replayId,
                            runtimeInstanceId,
                            acquiredAt,
                            acquiredAt,
                            expiresAt)),
                    "lease repository acquire result");
        } catch (Throwable failure) {
            handles.remove(replayId, handle);
            return CompletableFuture.failedFuture(failure);
        }

        CompletableFuture<LeaseRepository.LeaseRow> result = new CompletableFuture<>();
        acquireStage.whenComplete((row, failure) -> {
            if (failure != null) {
                handles.remove(replayId, handle);
                result.completeExceptionally(unwrap(failure));
                return;
            }
            if (row == null) {
                handles.remove(replayId, handle);
                result.completeExceptionally(new IllegalStateException("lease repository returned null"));
                return;
            }
            if (!runtimeInstanceId.equals(row.runtimeInstanceId())
                    || !replayId.equals(row.replayId())) {
                handles.remove(replayId, handle);
                result.completeExceptionally(
                        new IllegalStateException("lease repository returned an unexpected owner"));
                return;
            }
            if (closed.get() || !handle.activate(row)) {
                handles.remove(replayId, handle);
                try {
                    repository.release(replayId, runtimeInstanceId);
                } catch (Throwable ignored) {
                    // The acquire result is rejected locally; the repository
                    // cleanup remains best effort during shutdown or scheduler failure.
                }
                result.completeExceptionally(new IllegalStateException("recording lease manager is closed"));
                return;
            }
            result.complete(row);
        });
        return result;
    }

    /** Releases a locally active lease and stops its heartbeat. */
    public CompletionStage<Boolean> release(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        LeaseHandle handle = handles.get(replayId);
        if (handle == null || !handle.beginRelease()) {
            return CompletableFuture.completedFuture(false);
        }
        handles.remove(replayId, handle);
        try {
            return Objects.requireNonNull(
                    repository.release(replayId, runtimeInstanceId),
                    "lease repository release result");
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Releases all currently active leases and reports the first failure after all attempts. */
    public CompletionStage<Void> releaseAll() {
        List<ReplayId> replayIds = new ArrayList<>(handles.keySet());
        CompletableFuture<Void> result = new CompletableFuture<>();
        releaseNext(replayIds, 0, null, result);
        return result;
    }

    private void releaseNext(
            List<ReplayId> replayIds,
            int index,
            Throwable firstFailure,
            CompletableFuture<Void> result) {
        if (index == replayIds.size()) {
            if (firstFailure == null) {
                result.complete(null);
            } else {
                result.completeExceptionally(firstFailure);
            }
            return;
        }
        CompletionStage<Boolean> releaseStage;
        try {
            releaseStage = release(replayIds.get(index));
        } catch (Throwable failure) {
            releaseNext(replayIds, index + 1, firstFailure == null ? failure : firstFailure, result);
            return;
        }
        releaseStage.whenComplete((ignored, failure) -> releaseNext(
                replayIds,
                index + 1,
                firstFailure == null && failure != null ? unwrap(failure) : firstFailure,
                result));
    }

    /** Stops heartbeat scheduling and rejects new acquires. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (LeaseHandle handle : handles.values()) {
            handle.closeWithoutRepositoryCall();
            handles.remove(handle.replayId, handle);
        }
    }

    /** The callback invoked once when one runtime loses ownership of a lease. */
    @FunctionalInterface
    public interface LeaseLossHandler {
        void onLeaseLost(ReplayId replayId, Throwable cause);
    }

    private final class LeaseHandle {
        private final ReplayId replayId;
        private final LeaseLossHandler lossHandler;
        private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
        private final AtomicBoolean renewInFlight = new AtomicBoolean();
        private volatile ScheduledFuture<?> heartbeatTask;

        private LeaseHandle(ReplayId replayId, LeaseLossHandler lossHandler) {
            this.replayId = replayId;
            this.lossHandler = lossHandler;
        }

        private boolean activate(LeaseRepository.LeaseRow row) {
            if (!state.compareAndSet(State.PENDING, State.ACTIVE)) {
                return false;
            }
            try {
                heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(
                        () -> renew(row.replayId()),
                        heartbeatInterval.toNanos(),
                        heartbeatInterval.toNanos(),
                        TimeUnit.NANOSECONDS);
                return true;
            } catch (RuntimeException failure) {
                state.set(State.LOST);
                return false;
            }
        }

        private void renew(ReplayId replayId) {
            if (state.get() != State.ACTIVE || !renewInFlight.compareAndSet(false, true)) {
                return;
            }
            final Instant heartbeatAt;
            final Instant expiresAt;
            try {
                heartbeatAt = clock.instant();
                expiresAt = heartbeatAt.plus(leaseTtl);
            } catch (RuntimeException failure) {
                renewInFlight.set(false);
                lose(failure);
                return;
            }

            final CompletionStage<Boolean> renewal;
            try {
                renewal = Objects.requireNonNull(
                        repository.renew(new LeaseRepository.LeaseRenew(
                                replayId,
                                runtimeInstanceId,
                                heartbeatAt,
                                expiresAt)),
                        "lease repository renew result");
            } catch (Throwable failure) {
                renewInFlight.set(false);
                lose(failure);
                return;
            }
            renewal.whenComplete((renewed, failure) -> {
                renewInFlight.set(false);
                if (failure != null) {
                    lose(unwrap(failure));
                } else if (!Boolean.TRUE.equals(renewed)) {
                    lose(new LeaseRepository.LeaseNotActiveException(replayId));
                }
            });
        }

        private boolean beginRelease() {
            State previous = state.getAndUpdate(current ->
                    current == State.ACTIVE ? State.RELEASED : current);
            if (previous == State.ACTIVE) {
                cancelHeartbeat();
                return true;
            }
            return false;
        }

        private void lose(Throwable cause) {
            if (!state.compareAndSet(State.ACTIVE, State.LOST)) {
                return;
            }
            cancelHeartbeat();
            handles.remove(replayId, this);
            try {
                callbackExecutor.execute(() -> lossHandler.onLeaseLost(replayId, cause));
            } catch (Throwable ignored) {
                // A rejected diagnostic callback cannot restore an already lost lease.
            }
        }

        private void closeWithoutRepositoryCall() {
            State previous = state.getAndSet(State.CLOSED);
            if (previous == State.ACTIVE || previous == State.PENDING) {
                cancelHeartbeat();
            }
        }

        private void cancelHeartbeat() {
            ScheduledFuture<?> task = heartbeatTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    private enum State {
        PENDING,
        ACTIVE,
        RELEASED,
        LOST,
        CLOSED
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
