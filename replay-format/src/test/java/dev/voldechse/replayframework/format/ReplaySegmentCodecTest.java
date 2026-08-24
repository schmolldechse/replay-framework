package dev.voldechse.replayframework.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaySegmentCodecTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripPreservesHeaderFramesAndPayloadCopies() throws Exception {
        Path target = temporaryDirectory.resolve("00000000.segment");
        byte[] configurationPayload = {0x01, 0x02, 0x03};
        byte[] emptyPayload = {};
        byte[] playPayload = {0x7F, 0x00, (byte) 0xFF};

        SegmentHeader writtenHeader;
        try (ReplaySegmentWriter writer = new ReplaySegmentWriter(target, "paper-26.2")) {
            writer.append(new RawPacketFrame(
                    100L, 40L, 0, PacketPhase.CONFIGURATION, 3, configurationPayload));
            configurationPayload[0] = 0x55;
            writer.append(new RawPacketFrame(
                    200L, 40L, 1, PacketPhase.PLAY, 7, emptyPayload));
            writer.append(new RawPacketFrame(
                    300L, 41L, 0, PacketPhase.PLAY, 11, playPayload));
            writtenHeader = writer.finish();
        }

        ReplaySegmentReader.DecodedSegment decoded =
                new ReplaySegmentReader().read(target);

        assertNotNull(decoded);
        assertEquals(SegmentHeader.CURRENT_FORMAT_VERSION, writtenHeader.formatVersion());
        assertEquals("paper-26.2", writtenHeader.adapterId());
        assertEquals(SegmentHeader.CompressionId.ZSTD, writtenHeader.compressionId());
        assertEquals(100L, writtenHeader.startElapsedNanos());
        assertEquals(300L, writtenHeader.endElapsedNanos());
        assertEquals(3L, writtenHeader.frameCount());
        assertTrue(writtenHeader.uncompressedLength() > 0L);
        assertEquals(writtenHeader, decoded.header());
        assertEquals(3, decoded.frames().size());

        assertFrame(decoded.frames().get(0), 100L, 40L, 0,
                PacketPhase.CONFIGURATION, 3, new byte[]{0x01, 0x02, 0x03});
        assertFrame(decoded.frames().get(1), 200L, 40L, 1,
                PacketPhase.PLAY, 7, new byte[0]);
        assertFrame(decoded.frames().get(2), 300L, 41L, 0,
                PacketPhase.PLAY, 11, new byte[]{0x7F, 0x00, (byte) 0xFF});
    }

    @Test
    void truncatedSegmentFailsWithCorruptReplaySegmentException() throws Exception {
        Path target = temporaryDirectory.resolve("00000001.segment");
        try (ReplaySegmentWriter writer = new ReplaySegmentWriter(target, "paper-26.2")) {
            writer.append(new RawPacketFrame(
                    1L, 1L, 0, PacketPhase.PLAY, 1, new byte[]{0x01}));
            writer.append(new RawPacketFrame(
                    2L, 1L, 1, PacketPhase.PLAY, 2, new byte[]{0x02, 0x03}));
            writer.finish();
        }

        byte[] complete = Files.readAllBytes(target);
        Path truncated = temporaryDirectory.resolve("00000001-truncated.segment");
        Files.write(truncated, Arrays.copyOf(complete, complete.length - 5));

        assertThrows(
                CorruptReplaySegmentException.class,
                () -> new ReplaySegmentReader().read(truncated));
    }

    private static void assertFrame(
            RawPacketFrame actual,
            long elapsedNanos,
            long serverTick,
            int sequence,
            PacketPhase phase,
            int packetId,
            byte[] payload) {
        assertEquals(elapsedNanos, actual.elapsedNanos());
        assertEquals(serverTick, actual.serverTick());
        assertEquals(sequence, actual.sequence());
        assertEquals(phase, actual.phase());
        assertEquals(packetId, actual.packetId());
        assertArrayEquals(payload, actual.payload());
    }
}
