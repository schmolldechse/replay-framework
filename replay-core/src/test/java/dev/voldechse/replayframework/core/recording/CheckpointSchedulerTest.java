package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.core.capture.CapturedPacket;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CheckpointSchedulerTest {

    @Test
    void requestsOnePeriodicCheckpointAtTheFirstReachedInterval() {
        CheckpointScheduler scheduler = scheduler(Duration.ofNanos(10));

        assertTrue(scheduler.dueForFrame(packet(9L, 2L)).isEmpty());
        CheckpointEncoder.CheckpointRequest request = scheduler
                .dueForFrame(packet(10L, 2L))
                .orElseThrow();

        assertEquals(10L, request.elapsedNanos());
        assertEquals(CheckpointEncoder.CheckpointKind.PERIODIC, request.kind());
    }

    @Test
    void deduplicatesDimensionChangeAtTheSamePointAsPeriodicRequest() {
        CheckpointScheduler scheduler = scheduler(Duration.ofNanos(10));

        assertTrue(scheduler.dueForFrame(packet(10L, 4L)).isPresent());
        assertTrue(scheduler.dueForDimensionChange(10L, 4L).isEmpty());
    }

    @Test
    void assignsStrictlyIncreasingOrdinalsAfterInitialCheckpoint() {
        CheckpointScheduler scheduler = scheduler(Duration.ofNanos(10));
        scheduler.registerInitial(new ReplayCheckpoint(0, 0L, List.of()));

        ReplayCheckpoint first = new ReplayCheckpoint(1, 10L, List.of());
        scheduler.markWritten(first);
        assertEquals(2, scheduler.nextOrdinal());
    }

    private static CheckpointScheduler scheduler(Duration interval) {
        CheckpointEncoder encoder = request -> CompletableFuture.completedFuture(
                new ReplayCheckpoint(0, request.elapsedNanos(), List.of()));
        Executor executor = Runnable::run;
        return new CheckpointScheduler(
                interval,
                encoder,
                RecordingScope.builder().build(),
                (elapsedNanos, serverTick, kind) -> new CheckpointEncoder.CheckpointRequest(
                        RecordingScope.builder().build(), elapsedNanos, serverTick, kind));
    }

    private static CapturedPacket packet(long captureTimeNanos, long serverTick) {
        return new CapturedPacket(
                UUID.randomUUID(),
                captureTimeNanos,
                serverTick,
                0,
                PacketPhase.PLAY,
                1,
                new byte[] {1});
    }
}
