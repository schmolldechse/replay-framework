package dev.voldechse.replayframework.runtime;

import dev.voldechse.replayframework.core.diagnostics.ReplayDiagnosticsSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayShutdownCoordinatorTest {
    private ScheduledExecutorService scheduler;

    @AfterEach
    void closeScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void shutdownIsIdempotentAndClosesResourcesAfterSuccessfulPhases() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        List<String> calls = new ArrayList<>();
        ReplayShutdownCoordinator.ShutdownActions actions = new ReplayShutdownCoordinator.ShutdownActions() {
            @Override
            public CompletableFuture<Void> beginRecordingShutdown() {
                calls.add("recording");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> failOutstandingRecordings() {
                calls.add("fail");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> closePlaybacks() {
                calls.add("playback");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> releaseLeases() {
                calls.add("leases");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void closeSharedResources() {
                calls.add("resources");
            }
        };
        ReplayShutdownCoordinator coordinator = new ReplayShutdownCoordinator(
                Duration.ofSeconds(1), scheduler, actions, ReplayShutdownCoordinatorTest::snapshot);

        CompletableFuture<ReplayShutdownCoordinator.ShutdownResult> first =
                coordinator.shutdown().toCompletableFuture();
        assertSame(first, coordinator.shutdown().toCompletableFuture());
        ReplayShutdownCoordinator.ShutdownResult result = first.join();

        assertTrue(result.completedWithinTimeout());
        assertEquals(List.of("recording", "playback", "leases", "resources"), calls);
    }

    @Test
    void timeoutFailsOutstandingRecordingsBeforeSharedResourcesClose() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        List<String> calls = new ArrayList<>();
        CompletableFuture<Void> recording = new CompletableFuture<>();
        ReplayShutdownCoordinator.ShutdownActions actions = new ReplayShutdownCoordinator.ShutdownActions() {
            @Override
            public CompletableFuture<Void> beginRecordingShutdown() {
                calls.add("recording");
                return recording;
            }

            @Override
            public CompletableFuture<Void> failOutstandingRecordings() {
                calls.add("fail");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> closePlaybacks() {
                calls.add("playback");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> releaseLeases() {
                calls.add("leases");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void closeSharedResources() {
                calls.add("resources");
            }
        };
        ReplayShutdownCoordinator coordinator = new ReplayShutdownCoordinator(
                Duration.ofMillis(20), scheduler, actions, ReplayShutdownCoordinatorTest::snapshot);

        ReplayShutdownCoordinator.ShutdownResult result = coordinator.await(Duration.ofSeconds(1));

        assertFalse(result.completedWithinTimeout());
        assertTrue(calls.indexOf("fail") < calls.indexOf("resources"));
        assertEquals("resources", calls.get(calls.size() - 1));
    }

    private static ReplayDiagnosticsSnapshot snapshot() {
        return new ReplayDiagnosticsSnapshot(
                java.time.Instant.now(),
                List.of(),
                List.of(),
                0L,
                0L,
                0L,
                0L,
                0L,
                Optional.empty());
    }
}
