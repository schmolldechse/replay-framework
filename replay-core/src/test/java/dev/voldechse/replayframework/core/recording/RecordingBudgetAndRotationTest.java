package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.api.recording.ReplayBudget;
import dev.voldechse.replayframework.api.recording.ReplayCompletionReason;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.CheckpointSignalSource;
import dev.voldechse.replayframework.core.capture.CapturedPacket;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import dev.voldechse.replayframework.format.ReplayCheckpointReader;
import dev.voldechse.replayframework.format.ReplaySegmentReader;
import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecordingBudgetAndRotationTest {

    @Test
    void selectsTheFirstMatchingSessionBudgetAfterTheCompleteUnit() {
        ReplayBudget budget = ReplayBudget.builder()
                .maxSegmentBytes(64)
                .maxDuration(Duration.ofNanos(10))
                .maxTotalBytes(3)
                .maxPackets(1)
                .maxSegments(1)
                .build();
        RecordingBudgetTracker tracker = new RecordingBudgetTracker(budget);

        RawPacketFrame frame = frame(10L, new byte[] {1, 2, 3});
        RecordingBudgetTracker.BudgetObservation observation =
                tracker.afterFrame(frame, RecordingBudgetTracker.UnitKind.DELTA_FRAME);

        assertEquals(Optional.of(ReplayCompletionReason.DURATION_LIMIT),
                observation.completionReason());
        assertEquals(3L, observation.snapshot().payloadBytes());
        assertEquals(1L, observation.snapshot().packetCount());
        assertTrue(tracker.stopRequested());
    }

    @Test
    void segmentLimitIsSelectedWhenTheLastSegmentIsOpened() {
        ReplayBudget budget = ReplayBudget.builder()
                .maxSegmentBytes(64)
                .maxSegments(2)
                .build();
        RecordingBudgetTracker tracker = new RecordingBudgetTracker(budget);

        tracker.afterSegmentOpened(1L);
        RecordingBudgetTracker.BudgetObservation observation = tracker.afterSegmentOpened(2L);

        assertEquals(Optional.of(ReplayCompletionReason.SEGMENT_LIMIT),
                observation.completionReason());
        assertEquals(2L, observation.snapshot().openedSegmentCount());
    }

    @Test
    void rotatesWithoutDroppingFramesAndPublishesTheInitialCheckpoint(@TempDir Path workspace)
            throws Exception {
        SessionPacketQueue queue = new SessionPacketQueue(1024);
        queue.enqueue(packet(0L, 0, new byte[] {1, 2, 3, 4}));
        queue.enqueue(packet(1L, 1, new byte[] {5, 6, 7, 8}));
        queue.enqueue(packet(2L, 2, new byte[] {9, 10, 11, 12}));
        queue.closeForEnqueue();

        RecordingSegmentAppender appender = appender(
                workspace,
                queue,
                ReplayBudget.builder().maxSegmentBytes(18).build(),
                reason -> {
                },
                (code, cause) -> {
                    throw new AssertionError("unexpected writer failure: " + code, cause);
                });
        appender.runWriterLoop();
        RecordingSegmentAppender.RecordingArtifacts artifacts = appender.finishForFinalization();

        List<RawPacketFrame> frames = new ArrayList<>();
        ReplaySegmentReader reader = new ReplaySegmentReader();
        for (Path segment : artifacts.segmentFiles()) {
            frames.addAll(reader.read(segment).frames());
        }
        assertEquals(3, frames.size());
        assertEquals(List.of(0, 1, 2), frames.stream().map(RawPacketFrame::sequence).toList());
        assertEquals(1, artifacts.checkpointFiles().size());
        assertEquals(0, new ReplayCheckpointReader()
                .read(artifacts.checkpointFiles().get(0)).ordinal());
    }

    @Test
    void drainsPacketsAcceptedBeforeTheBudgetStop(@TempDir Path workspace) {
        SessionPacketQueue queue = new SessionPacketQueue(1024);
        queue.enqueue(packet(0L, 0, new byte[] {1}));
        queue.enqueue(packet(1L, 1, new byte[] {2}));
        queue.closeForEnqueue();
        List<ReplayCompletionReason> reasons = new ArrayList<>();

        RecordingSegmentAppender appender = appender(
                workspace,
                queue,
                ReplayBudget.builder().maxSegmentBytes(128).maxPackets(1).build(),
                reasons::add,
                (code, cause) -> {
                    throw new AssertionError("unexpected writer failure: " + code, cause);
                });
        appender.runWriterLoop();
        RecordingSegmentAppender.RecordingArtifacts artifacts = appender.finishForFinalization();

        assertEquals(List.of(ReplayCompletionReason.PACKET_LIMIT), reasons);
        assertEquals(2L, artifacts.packetCount());
    }

    @Test
    void persistsPeriodicCheckpointWithCoreOwnedOrdinal(@TempDir Path workspace) throws Exception {
        SessionPacketQueue queue = new SessionPacketQueue(1024);
        queue.enqueue(packet(10L, 10, new byte[] {1}));
        queue.closeForEnqueue();

        CheckpointEncoder encoder = request -> java.util.concurrent.CompletableFuture.completedFuture(
                new ReplayCheckpoint(
                        1,
                        request.elapsedNanos(),
                        List.of(new RawPacketFrame(
                                request.elapsedNanos(), request.serverTick(), 0,
                                PacketPhase.PLAY, 2, new byte[] {9}))));
        CheckpointScheduler scheduler = new CheckpointScheduler(
                Duration.ofNanos(10),
                encoder,
                dev.voldechse.replayframework.api.recording.RecordingScope.builder().build(),
                (elapsedNanos, serverTick, kind) -> new CheckpointEncoder.CheckpointRequest(
                        dev.voldechse.replayframework.api.recording.RecordingScope.builder().build(),
                        elapsedNanos, serverTick, kind));
        RecordingSegmentAppender appender = new RecordingSegmentAppender(
                workspace,
                "test-adapter",
                0L,
                queue,
                new ReplayCheckpoint(0, 0L, List.of()),
                ReplayBudget.builder().maxSegmentBytes(1).build(),
                scheduler,
                CheckpointSignalSource.noop(),
                reason -> {
                },
                (code, cause) -> {
                    throw new AssertionError("unexpected writer failure: " + code, cause);
                },
                artifacts -> {
                });

        appender.runWriterLoop();
        RecordingSegmentAppender.RecordingArtifacts artifacts = appender.finishForFinalization();

        assertEquals(2L, artifacts.checkpointCount());
        assertEquals(2L, artifacts.packetCount());
        assertEquals(List.of(0, 1), artifacts.checkpointFiles().stream()
                .map(path -> {
                    try {
                        return new ReplayCheckpointReader().read(path).ordinal();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }).toList());
        assertEquals(0, artifacts.seekPoints().get(artifacts.seekPoints().size() - 1).segmentOrdinal());
        assertTrue(artifacts.seekPoints().get(artifacts.seekPoints().size() - 1).frameOffset() > 0L);
    }

    @Test
    void keepsAnOversizedSinglePayloadIntact(@TempDir Path workspace) throws Exception {
        byte[] payload = new byte[128];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) index;
        }
        SessionPacketQueue queue = new SessionPacketQueue(1024);
        queue.enqueue(packet(0L, 0, payload));
        queue.closeForEnqueue();

        RecordingSegmentAppender appender = appender(
                workspace,
                queue,
                ReplayBudget.builder().maxSegmentBytes(1).build(),
                reason -> {
                },
                (code, cause) -> {
                    throw new AssertionError("unexpected writer failure: " + code, cause);
                });
        appender.runWriterLoop();

        RawPacketFrame decoded = new ReplaySegmentReader()
                .read(appender.finishForFinalization().segmentFiles().get(0))
                .frames().get(0);
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void startsTheWriterExactlyOnceOnAVirtualThread(@TempDir Path workspace) throws Exception {
        SessionPacketQueue queue = new SessionPacketQueue(1024);
        queue.closeForEnqueue();
        RecordingSegmentAppender appender = appender(
                workspace,
                queue,
                ReplayBudget.builder().maxSegmentBytes(128).build(),
                reason -> {
                },
                (code, cause) -> {
                    throw new AssertionError("unexpected writer failure: " + code, cause);
                });

        Thread writer = appender.startWriter();
        writer.join();

        assertEquals(1L, appender.finishForFinalization().checkpointCount());
    }

    private static RecordingSegmentAppender appender(
            Path workspace,
            SessionPacketQueue queue,
            ReplayBudget budget,
            java.util.function.Consumer<ReplayCompletionReason> budgetStop,
            java.util.function.BiConsumer<ReplayFailureCode, Throwable> failure) {
        CheckpointEncoder encoder = request -> java.util.concurrent.CompletableFuture.completedFuture(
                new ReplayCheckpoint(0, request.elapsedNanos(), List.of()));
        CheckpointScheduler scheduler = new CheckpointScheduler(
                Duration.ofHours(1),
                encoder,
                dev.voldechse.replayframework.api.recording.RecordingScope.builder().build(),
                (elapsedNanos, serverTick, kind) -> new CheckpointEncoder.CheckpointRequest(
                        dev.voldechse.replayframework.api.recording.RecordingScope.builder().build(),
                        elapsedNanos,
                        serverTick,
                        kind));
        return new RecordingSegmentAppender(
                workspace,
                "test-adapter",
                0L,
                queue,
                new ReplayCheckpoint(0, 0L, List.of()),
                budget,
                scheduler,
                CheckpointSignalSource.noop(),
                budgetStop,
                failure,
                artifacts -> {
                });
    }

    private static CapturedPacket packet(long captureTimeNanos, int sequence, byte[] payload) {
        return new CapturedPacket(
                UUID.randomUUID(), captureTimeNanos, sequence, sequence,
                PacketPhase.PLAY, 1, payload);
    }

    private static RawPacketFrame frame(long elapsedNanos, byte[] payload) {
        return new RawPacketFrame(elapsedNanos, 1L, 0, PacketPhase.PLAY, 1, payload);
    }
}
