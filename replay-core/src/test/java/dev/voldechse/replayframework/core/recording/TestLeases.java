package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.core.port.LeaseRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** A lease manager whose leases always succeed, for tests of the recording composition. */
final class TestLeases {

    private static final ScheduledExecutorService HEARTBEATS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "test-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private TestLeases() {
    }

    /** A lease manager that grants and releases every lease at once. */
    static RecordingLeaseManager manager() {
        return new RecordingLeaseManager(
                new Granting(),
                Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                HEARTBEATS,
                Runnable::run,
                UUID.fromString("55555555-5555-5555-5555-555555555555"));
    }

    /** Grants, renews and releases every lease. */
    private static final class Granting implements LeaseRepository {
        @Override
        public CompletionStage<LeaseRow> acquire(LeaseAcquire command) {
            return CompletableFuture.completedFuture(new LeaseRow(
                    command.replayId(),
                    command.runtimeInstanceId(),
                    command.acquiredAt(),
                    command.heartbeatAt(),
                    command.expiresAt(),
                    0L));
        }

        @Override
        public CompletionStage<Boolean> renew(LeaseRenew command) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> release(ReplayId replayId, UUID runtimeInstanceId) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<List<LeaseRow>> findExpired(Instant now) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
