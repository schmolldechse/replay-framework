package dev.voldechse.replayframework.core.port;

import dev.voldechse.replayframework.api.id.ReplayId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Internal asynchronous persistence port for exclusive recording leases. */
public interface LeaseRepository {

    /** Acquires a lease or fails when another active owner holds it. */
    CompletionStage<LeaseRow> acquire(LeaseAcquire command);

    /** Renews a lease only when the supplied runtime is its owner. */
    CompletionStage<Boolean> renew(LeaseRenew command);

    /** Releases a lease only when the supplied runtime is its owner. */
    CompletionStage<Boolean> release(ReplayId replayId, UUID runtimeInstanceId);

    /** Finds expired leases without mutating them. */
    CompletionStage<List<LeaseRow>> findExpired(Instant now);

    /** Immutable acquire request. */
    record LeaseAcquire(
            ReplayId replayId,
            UUID runtimeInstanceId,
            Instant acquiredAt,
            Instant heartbeatAt,
            Instant expiresAt) {

        /** Validates lease ownership and monotonic timestamps. */
        public LeaseAcquire {
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(runtimeInstanceId, "runtimeInstanceId");
            Objects.requireNonNull(acquiredAt, "acquiredAt");
            Objects.requireNonNull(heartbeatAt, "heartbeatAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            requireTimeOrder(acquiredAt, heartbeatAt, expiresAt);
        }
    }

    /** Immutable renewal request. */
    record LeaseRenew(
            ReplayId replayId,
            UUID runtimeInstanceId,
            Instant heartbeatAt,
            Instant expiresAt) {

        /** Validates the new heartbeat and expiry order. */
        public LeaseRenew {
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(runtimeInstanceId, "runtimeInstanceId");
            Objects.requireNonNull(heartbeatAt, "heartbeatAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(heartbeatAt)) {
                throw new IllegalArgumentException("expiresAt must be after heartbeatAt");
            }
        }
    }

    /** Immutable lease read model. */
    record LeaseRow(
            ReplayId replayId,
            UUID runtimeInstanceId,
            Instant acquiredAt,
            Instant heartbeatAt,
            Instant expiresAt,
            long entityVersion) {

        /** Validates persisted lease invariants before returning to core. */
        public LeaseRow {
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(runtimeInstanceId, "runtimeInstanceId");
            Objects.requireNonNull(acquiredAt, "acquiredAt");
            Objects.requireNonNull(heartbeatAt, "heartbeatAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            requireTimeOrder(acquiredAt, heartbeatAt, expiresAt);
            if (entityVersion < 0) {
                throw new IllegalArgumentException("entityVersion must not be negative");
            }
        }
    }

    /** Indicates that another runtime owns an unexpired lease. */
    final class LeaseAlreadyHeldException extends RuntimeException {
        public LeaseAlreadyHeldException(ReplayId replayId) {
            super("recording lease is already held for replay: " + replayId);
        }
    }

    /** Indicates that a lease operation targets a replay without an active recording lease. */
    final class LeaseNotActiveException extends RuntimeException {
        public LeaseNotActiveException(ReplayId replayId) {
            super("recording lease is not active for replay: " + replayId);
        }
    }

    private static void requireTimeOrder(Instant acquiredAt, Instant heartbeatAt, Instant expiresAt) {
        if (heartbeatAt.isBefore(acquiredAt)) {
            throw new IllegalArgumentException("heartbeatAt must not precede acquiredAt");
        }
        if (!expiresAt.isAfter(heartbeatAt)) {
            throw new IllegalArgumentException("expiresAt must be after heartbeatAt");
        }
    }
}
