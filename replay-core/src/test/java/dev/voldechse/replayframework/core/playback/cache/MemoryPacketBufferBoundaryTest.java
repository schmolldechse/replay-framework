package dev.voldechse.replayframework.core.playback.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.SeekPoint;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryPacketBufferBoundaryTest {

    @Test
    void checkpointOffsetIsAnExclusiveBoundaryEvenAtTheSameTimestamp() {
        MemoryPacketBuffer buffer = new MemoryPacketBuffer(64L * 1024L);
        RawPacketFrame before = frame(100L, 10L, 0, 1);
        RawPacketFrame atBoundary = frame(100L, 10L, 1, 2);
        RawPacketFrame after = frame(101L, 10L, 2, 3);
        buffer.addSegment(
                2,
                List.of(before, atBoundary, after),
                List.of(0L, 12L, 24L));

        List<RawPacketFrame> result = buffer.framesAfter(
                new SeekPoint(100L, 1, 2, 12L),
                Duration.ofNanos(102L));

        assertEquals(List.of(atBoundary, after), result);
    }

    @Test
    void laterSegmentsRemainPartOfCheckpointReconstruction() {
        MemoryPacketBuffer buffer = new MemoryPacketBuffer(64L * 1024L);
        RawPacketFrame anchorTail = frame(100L, 10L, 1, 2);
        RawPacketFrame laterSegment = frame(101L, 11L, 0, 3);
        buffer.addSegment(2, List.of(anchorTail), List.of(12L));
        buffer.addSegment(3, List.of(laterSegment), List.of(0L));

        assertEquals(
                List.of(anchorTail, laterSegment),
                buffer.framesAfter(
                        new SeekPoint(100L, 1, 2, 12L),
                        Duration.ofNanos(102L)));
    }

    private static RawPacketFrame frame(
            long elapsedNanos,
            long serverTick,
            int sequence,
            int packetId) {
        return new RawPacketFrame(
                elapsedNanos,
                serverTick,
                sequence,
                PacketPhase.PLAY,
                packetId,
                new byte[] {(byte) packetId});
    }
}
