package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class Paper26_2CaptureBridgeTest {

    @Test
    void copiesTheFinalPacketEventsBufferIncludingARewrittenPacketId() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeByte(99);
            buffer.writeByte(0xAC);
            buffer.writeByte(0x02);
            buffer.writeBytes(new byte[] {4, 5, 6});
            buffer.readerIndex(1);

            Paper26_2CaptureBridge.ObservedWirePacket packet =
                    Paper26_2CaptureBridge.copyWirePacket(buffer);

            assertEquals(300, packet.packetId());
            assertArrayEquals(new byte[] {4, 5, 6}, packet.payload());
            assertEquals(1, buffer.readerIndex());
        } finally {
            buffer.release();
        }
    }
}


