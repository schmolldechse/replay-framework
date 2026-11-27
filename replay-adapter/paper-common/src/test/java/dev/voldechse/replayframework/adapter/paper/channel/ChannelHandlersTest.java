package dev.voldechse.replayframework.adapter.paper.channel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The shared channel handlers in a pipeline shaped like a Paper player connection. */
class ChannelHandlersTest {

    private final List<String> passed = new ArrayList<>();
    private final List<Object> tapped = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();

    @Test
    void theTapSeesEveryPacketObjectAndPassesItOn() {
        EmbeddedChannel channel = connection(false);
        ClientboundTap.install(channel.pipeline(), tap());

        channel.writeOutbound("chunk");
        channel.writeOutbound(List.of("spawn", "metadata"));

        assertEquals(List.of("chunk", "spawn", "metadata"), tapped);
        assertEquals("chunk", channel.readOutbound());
        assertEquals(List.of("spawn", "metadata"), channel.readOutbound());
    }

    @Test
    void theTapIgnoresBytesAndKeepsPassingWhenItsListenerFails() {
        EmbeddedChannel channel = connection(false);
        ClientboundTap.install(channel.pipeline(), new ClientboundTap(
                List::of, packet -> { throw new IllegalStateException("broken listener"); },
                () -> { }, failures::add));
        ByteBuf bytes = Unpooled.wrappedBuffer(new byte[] {1, 2});

        channel.writeOutbound(bytes);
        channel.writeOutbound("chunk");

        assertSame(bytes, channel.readOutbound());
        assertEquals("chunk", channel.readOutbound());
        assertEquals(1, failures.size());
        bytes.release();
    }

    @Test
    void theTapSitsAtTheTailBeforeTheServerConnection() {
        EmbeddedChannel channel = connection(false);
        ClientboundTap.install(channel.pipeline(), tap());
        ClientboundTap.install(channel.pipeline(), tap());

        List<String> names = channel.pipeline().names();
        assertEquals(names.indexOf(PipelineNames.PACKET_HANDLER) - 1, names.indexOf(PipelineNames.CLIENTBOUND_TAP));
        assertEquals(1, names.stream().filter(PipelineNames.CLIENTBOUND_TAP::equals).count());
    }

    @Test
    void replayBytesSkipTheEncoderAndEverythingBehindIt() {
        EmbeddedChannel channel = connection(true);
        ClientboundTap.install(channel.pipeline(), tap());
        ReplayChannelWriter writer = new ReplayChannelWriter(channel, failures::add);

        writer.install();
        writer.write(new PacketCodec.EncodedPacket(
                PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 300, new byte[] {7, 8}));
        writer.flush();

        List<String> names = channel.pipeline().names();
        assertEquals(names.indexOf(PipelineNames.COMPRESS) + 1, names.indexOf(PipelineNames.WRITE_ANCHOR));
        assertEquals(List.of("compress", "prepender"), passed);
        assertTrue(tapped.isEmpty());
        ByteBuf written = channel.readOutbound();
        assertArrayEquals(new byte[] {(byte) 0xAC, 0x02, 7, 8}, ByteBufUtil.getBytes(written));
        written.release();
        assertTrue(failures.isEmpty());
    }

    @Test
    void withoutCompressionTheAnchorFollowsTheLengthPrepender() {
        EmbeddedChannel channel = connection(false);
        ReplayChannelWriter writer = new ReplayChannelWriter(channel, failures::add);

        writer.install();
        writer.write(new PacketCodec.EncodedPacket(
                PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 1, new byte[0]));
        writer.flush();
        writer.uninstall();

        List<String> names = channel.pipeline().names();
        assertTrue(!names.contains(PipelineNames.WRITE_ANCHOR));
        assertEquals(List.of("prepender"), passed);
        ((ByteBuf) channel.readOutbound()).release();
    }

    private ClientboundTap tap() {
        return new ClientboundTap(
                message -> message instanceof List<?> bundle ? List.copyOf(bundle) : List.of(message),
                tapped::add, () -> { }, failures::add);
    }

    /** prepender, [compress], encoder, packet_handler, as Paper builds them. */
    private EmbeddedChannel connection(boolean compressed) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(PipelineNames.PREPENDER, recording("prepender"));
        if (compressed) {
            channel.pipeline().addLast(PipelineNames.COMPRESS, recording("compress"));
        }
        channel.pipeline().addLast(PipelineNames.ENCODER, recording("encoder"));
        channel.pipeline().addLast(PipelineNames.PACKET_HANDLER, new ChannelInboundHandlerAdapter());
        return channel;
    }

    /** An outbound handler that notes when a message passes it. */
    private ChannelOutboundHandlerAdapter recording(String name) {
        return new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                if (message instanceof ByteBuf) {
                    passed.add(name);
                }
                context.write(message, promise);
            }
        };
    }
}
