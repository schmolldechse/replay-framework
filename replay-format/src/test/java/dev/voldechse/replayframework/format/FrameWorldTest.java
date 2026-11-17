package dev.voldechse.replayframework.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A recording can span several worlds; every frame keeps the world it belongs to. */
class FrameWorldTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @TempDir
    Path directory;

    @Test
    void segmentsKeepTheWorldOfEveryFrame() throws Exception {
        List<RawPacketFrame> written = List.of(
                frame(100L, 0, Optional.of(OVERWORLD)),
                frame(200L, 1, Optional.empty()),
                frame(300L, 2, Optional.of(NETHER)));
        Path target = directory.resolve("00000000.segment");
        try (ReplaySegmentWriter writer = new ReplaySegmentWriter(target, "paper-26.2")) {
            for (RawPacketFrame frame : written) {
                writer.append(frame);
            }
            writer.finish();
        }

        assertEquals(written, new ReplaySegmentReader().read(target).frames());
    }

    @Test
    void checkpointsKeepTheWorldOfEveryFrame() throws Exception {
        ReplayCheckpoint written = new ReplayCheckpoint(0, 0L, List.of(
                frame(0L, 0, Optional.of(OVERWORLD)),
                frame(0L, 1, Optional.of(NETHER)),
                frame(0L, 2, Optional.empty())));
        Path target = directory.resolve("00000000.checkpoint");
        new ReplayCheckpointWriter(target, "paper-26.2").write(written);

        assertEquals(written, new ReplayCheckpointReader().read(target));
    }

    @Test
    void appliesInItsOwnWorldOrInEveryWorldWithoutOne() {
        assertTrue(frame(0L, 0, Optional.of(NETHER)).appliesTo(NETHER));
        assertFalse(frame(0L, 0, Optional.of(NETHER)).appliesTo(OVERWORLD));
        assertTrue(frame(0L, 0, Optional.empty()).appliesTo(OVERWORLD));
        assertTrue(frame(0L, 0, Optional.empty()).appliesTo(NETHER));
    }

    @Test
    void rejectsBlankAndOverlongWorldKeys() {
        assertThrows(IllegalArgumentException.class, () -> frame(0L, 0, Optional.of(" ")));
        assertThrows(IllegalArgumentException.class,
                () -> frame(0L, 0, Optional.of("minecraft:" + "x".repeat(300))));
    }

    private static RawPacketFrame frame(long elapsedNanos, int sequence, Optional<String> world) {
        return new RawPacketFrame(
                elapsedNanos, 10L, sequence, PacketPhase.PLAY, 45, new byte[] {1, 2, 3}, world);
    }
}
