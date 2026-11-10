package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PacketEventsReplayWriterTest {

    private static final String PACKET_EVENTS_ENCODER = "packet-events-encoder";

    @Test
    void writesFramedBytesBehindPacketEventsPaperAndTheReplayGate() {
        AtomicInteger packetEventsWrites = new AtomicInteger();
        AtomicInteger paperWrites = new AtomicInteger();
        AtomicInteger gateWrites = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(PACKET_EVENTS_ENCODER, counter(packetEventsWrites));
        channel.pipeline().addLast("encoder", counter(paperWrites));
        channel.pipeline().addLast(ReplayViewerOutboundGate.HANDLER_NAME, counter(gateWrites));
        PacketEventsReplayWriter writer = new PacketEventsReplayWriter(
                channel, PACKET_EVENTS_ENCODER, failure::set);

        writer.write(new PacketCodec.EncodedPacket(
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                300,
                new byte[] {4, 5, 6}));
        writer.flush();
        channel.runPendingTasks();
        assertNull(failure.get());

        ByteBuf outbound = channel.readOutbound();
        try {
            byte[] actual = new byte[outbound.readableBytes()];
            outbound.readBytes(actual);
            assertArrayEquals(new byte[] {(byte) 0xAC, 0x02, 4, 5, 6}, actual);
        } finally {
            outbound.release();
            channel.finishAndReleaseAll();
        }
        assertEquals(0, packetEventsWrites.get());
        assertEquals(0, paperWrites.get());
        assertEquals(0, gateWrites.get());
        assertNull(failure.get());
    }

    private static ChannelOutboundHandlerAdapter counter(AtomicInteger writes) {
        return new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                writes.incrementAndGet();
                context.write(message, promise);
            }
        };
    }
}


