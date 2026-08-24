package dev.voldechse.replayframework.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayCheckpointCodecTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripPreservesCheckpointAndDefensivelyCopiesPayload() throws Exception {
        Path target = temporaryDirectory.resolve("00000000.checkpoint");
        byte[] configurationPayload = {0x01, 0x02, 0x03};
        ReplayCheckpoint checkpoint = new ReplayCheckpoint(
                4,
                20_000L,
                List.of(
                        new RawPacketFrame(
                                10_000L, 40L, 0,
                                PacketPhase.CONFIGURATION, 3, configurationPayload),
                        new RawPacketFrame(
                                20_000L, 41L, 0,
                                PacketPhase.PLAY, 7, new byte[]{0x7F, (byte) 0xFF})));
        configurationPayload[0] = 0x55;

        new ReplayCheckpointWriter(target, "paper-26.2").write(checkpoint);

        ReplayCheckpoint decoded = new ReplayCheckpointReader().read(target);

        assertEquals(checkpoint.ordinal(), decoded.ordinal());
        assertEquals(checkpoint.elapsedNanos(), decoded.elapsedNanos());
        assertEquals(checkpoint.initializationFrames().size(), decoded.initializationFrames().size());
        assertArrayEquals(
                new byte[]{0x01, 0x02, 0x03},
                decoded.initializationFrames().get(0).payload());
        assertArrayEquals(
                new byte[]{0x7F, (byte) 0xFF},
                decoded.initializationFrames().get(1).payload());
    }

    @Test
    void truncatedCheckpointFailsAsCorruptArtifact() throws Exception {
        Path target = temporaryDirectory.resolve("00000001.checkpoint");
        ReplayCheckpoint checkpoint = new ReplayCheckpoint(
                1,
                100L,
                List.of(new RawPacketFrame(
                        100L, 1L, 0, PacketPhase.PLAY, 1, new byte[]{0x01, 0x02})));
        new ReplayCheckpointWriter(target, "paper-26.2").write(checkpoint);

        byte[] complete = Files.readAllBytes(target);
        Path truncated = temporaryDirectory.resolve("00000001-truncated.checkpoint");
        Files.write(truncated, complete, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try (var channel = Files.newByteChannel(truncated, StandardOpenOption.WRITE)) {
            channel.truncate(Math.max(1L, complete.length - 5L));
        }

        assertThrows(
                CorruptReplayArtifactException.class,
                () -> new ReplayCheckpointReader().read(truncated));
    }

    @Test
    void trailingBytesAfterCheckpointStreamFailAsCorruptArtifact() throws Exception {
        Path target = temporaryDirectory.resolve("00000002.checkpoint");
        ReplayCheckpoint checkpoint = new ReplayCheckpoint(
                2,
                100L,
                List.of(new RawPacketFrame(
                        100L, 1L, 0, PacketPhase.PLAY, 1, new byte[]{0x01})));
        new ReplayCheckpointWriter(target, "paper-26.2").write(checkpoint);
        Files.write(target, new byte[]{0x42}, StandardOpenOption.APPEND);

        assertThrows(
                CorruptReplayArtifactException.class,
                () -> new ReplayCheckpointReader().read(target));
    }
}
