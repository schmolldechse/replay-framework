package dev.voldechse.replayframework.core.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.CheckpointSignalSource;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.api.recording.ReplayBudget;
import dev.voldechse.replayframework.core.capture.CapturedPacket;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import dev.voldechse.replayframework.format.ReplayCheckpointReader;
import dev.voldechse.replayframework.format.ReplaySegmentReader;
import dev.voldechse.replayframework.format.SeekPoint;
import dev.voldechse.replayframework.format.TrackDescriptor;
import dev.voldechse.replayframework.format.TrackKind;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrackRecordingTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void routesFramesToTheTrackOfTheirWorld(@TempDir Path workspace) throws Exception {
        SessionPacketQueue queue = new SessionPacketQueue(1024);
        queue.enqueue(packet(10L, 0, Optional.of(OVERWORLD), false));
        queue.enqueue(packet(20L, 1, Optional.of(NETHER), false));
        queue.enqueue(packet(30L, 2, Optional.of(OVERWORLD), true));
        queue.enqueue(packet(40L, 3, Optional.of(OVERWORLD), false));
        queue.closeForEnqueue();
        ReplayCheckpoint initial = new ReplayCheckpoint(0, 0L, List.of(
                new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 9, new byte[] {1}, Optional.of(OVERWORLD)),
                new RawPacketFrame(0L, 0L, 1, PacketPhase.PLAY, 8, new byte[] {2})));

        RecordingSegmentAppender appender = appender(workspace, queue, initial);
        appender.runWriterLoop();
        RecordingSegmentAppender.RecordingArtifacts artifacts = appender.finishForFinalization();

        List<TrackWriter.TrackArtifacts> tracks = artifacts.tracks();
        assertEquals(List.of(TrackKind.GLOBAL, TrackKind.WORLD, TrackKind.WORLD),
                tracks.stream().map(track -> track.descriptor().kind()).toList());
        assertEquals(Optional.of(OVERWORLD), tracks.get(1).descriptor().world());
        assertEquals(Optional.of(NETHER), tracks.get(2).descriptor().world());

        assertEquals(List.of(2), sequences(tracks.get(0)));
        assertEquals(List.of(0, 3), sequences(tracks.get(1)));
        assertEquals(List.of(1), sequences(tracks.get(2)));
        assertEquals(3L, artifacts.segmentCount());
        assertEquals(1L, artifacts.checkpointCount());
    }

    @Test
    void splitsCheckpointsByTrackAndGivesALateTrackEmptyOnes(@TempDir Path workspace) throws Exception {
        SessionPacketQueue queue = new SessionPacketQueue(1024);
        queue.enqueue(packet(20L, 0, Optional.of(NETHER), false));
        queue.closeForEnqueue();
        ReplayCheckpoint initial = new ReplayCheckpoint(0, 0L, List.of(
                new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 9, new byte[] {1}, Optional.of(OVERWORLD)),
                new RawPacketFrame(0L, 0L, 1, PacketPhase.PLAY, 8, new byte[] {2})));

        RecordingSegmentAppender appender = appender(workspace, queue, initial);
        appender.runWriterLoop();
        List<TrackWriter.TrackArtifacts> tracks = appender.finishForFinalization().tracks();

        assertEquals(List.of(1), checkpointSequences(tracks.get(0)));
        assertEquals(List.of(0), checkpointSequences(tracks.get(1)));
        assertEquals(List.of(), checkpointSequences(tracks.get(2)));
        for (TrackWriter.TrackArtifacts track : tracks) {
            assertEquals(new SeekPoint(0L, 0, 0, 0L), track.seekPoints().getFirst());
            assertTrue(track.checkpointFiles().getFirst().startsWith(
                    workspace.toRealPath().resolve(track.descriptor().directory())));
        }
    }

    private static List<Integer> sequences(TrackWriter.TrackArtifacts track) throws Exception {
        List<Integer> sequences = new ArrayList<>();
        for (Path segment : track.segmentFiles()) {
            for (RawPacketFrame frame : new ReplaySegmentReader().read(segment, track.descriptor()).frames()) {
                assertEquals(track.descriptor().frameWorld(), frame.world());
                sequences.add(frame.sequence());
            }
        }
        return sequences;
    }

    private static List<Integer> checkpointSequences(TrackWriter.TrackArtifacts track) throws Exception {
        return new ReplayCheckpointReader()
                .read(track.checkpointFiles().getFirst(), track.descriptor())
                .initializationFrames().stream()
                .map(RawPacketFrame::sequence)
                .toList();
    }

    private static CapturedPacket packet(
            long time, int sequence, Optional<String> world, boolean worldWide) {
        CaptureContext context = new CaptureContext(
                Optional.empty(), world.map(Key::key), Optional.empty(), Optional.empty(), Optional.empty());
        if (worldWide) {
            context = context.asWorldWide();
        }
        return new CapturedPacket(time, 1L, sequence, PacketPhase.PLAY, 1, new byte[] {7}, context);
    }

    private static RecordingSegmentAppender appender(
            Path workspace, SessionPacketQueue queue, ReplayCheckpoint initial) {
        CheckpointEncoder encoder = request -> CompletableFuture.completedFuture(
                new ReplayCheckpoint(0, request.elapsedNanos(), List.of()));
        CheckpointScheduler scheduler = new CheckpointScheduler(
                Duration.ofHours(1),
                encoder,
                RecordingScope.builder().build(),
                (elapsedNanos, serverTick, kind) -> new CheckpointEncoder.CheckpointRequest(
                        RecordingScope.builder().build(), elapsedNanos, serverTick, kind));
        return new RecordingSegmentAppender(
                workspace,
                "test-adapter",
                0L,
                queue,
                initial,
                ReplayBudget.builder().maxSegmentBytes(1024).build(),
                scheduler,
                CheckpointSignalSource.noop(),
                reason -> {
                },
                (code, cause) -> {
                    throw new AssertionError("unexpected writer failure: " + code, cause);
                },
                artifacts -> {
                });
    }
}
