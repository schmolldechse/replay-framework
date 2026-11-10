package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import com.github.retrooper.packetevents.PacketEvents;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.v26_3.capture.PaperConnectionAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Injects an already encoded replay packet behind Paper and PacketEvents.
 * Resolving the context for every operation is intentional because Paper can
 * relocate compression handlers while a connection is active.
 */
final class PacketEventsReplayWriter {

    private static final String PAPER_ENCODER_NAME = "encoder";

    private final Channel channel;
    private final String packetEventsEncoderName;
    private final Consumer<Throwable> failureHandler;

    PacketEventsReplayWriter(
            PaperConnectionAccessor.ConnectionHandle connection,
            Consumer<Throwable> failureHandler) {
        this(
                Objects.requireNonNull(connection, "connection").channel(),
                Objects.requireNonNull(PacketEvents.ENCODER_NAME, "PacketEvents encoder name"),
                failureHandler);
    }

    /** Constructor for focused pipeline tests. */
    PacketEventsReplayWriter(
            Channel channel,
            String packetEventsEncoderName,
            Consumer<Throwable> failureHandler) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.packetEventsEncoderName = Objects.requireNonNull(
                packetEventsEncoderName, "packetEventsEncoderName");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    void write(PacketCodec.EncodedPacket packet) {
        Objects.requireNonNull(packet, "packet");
        if (!channel.eventLoop().inEventLoop()) {
            channel.eventLoop().execute(() -> write(packet));
            return;
        }

        ByteBuf buffer = null;
        try {
            ChannelHandlerContext packetEventsContext = packetEventsContext(channel.pipeline());
            buffer = channel.alloc().buffer(varIntSize(packet.packetId()) + packet.payload().length);
            writeVarInt(buffer, packet.packetId());
            buffer.writeBytes(packet.payload());

            ChannelPromise promise = packetEventsContext.newPromise();
            promise.addListener(future -> {
                if (!future.isSuccess()) {
                    report(future.cause());
                }
            });
            packetEventsContext.write(buffer, promise);
            buffer = null;
        } catch (Throwable failure) {
            report(failure);
        } finally {
            ReferenceCountUtil.release(buffer);
        }
    }

    void flush() {
        if (!channel.eventLoop().inEventLoop()) {
            channel.eventLoop().execute(this::flush);
            return;
        }
        try {
            packetEventsContext(channel.pipeline()).flush();
        } catch (Throwable failure) {
            report(failure);
        }
    }

    private ChannelHandlerContext packetEventsContext(ChannelPipeline pipeline) {
        ChannelHandlerContext packetEvents = pipeline.context(packetEventsEncoderName);
        ChannelHandlerContext paperEncoder = pipeline.context(PAPER_ENCODER_NAME);
        ChannelHandlerContext replayGate = pipeline.context(ReplayViewerOutboundGate.HANDLER_NAME);
        if (packetEvents == null || paperEncoder == null || replayGate == null) {
            throw new IllegalStateException(
                    "PacketEvents encoder, Paper encoder or replay gate is missing");
        }

        List<String> names = pipeline.names();
        int packetEventsIndex = names.indexOf(packetEvents.name());
        int paperEncoderIndex = names.indexOf(paperEncoder.name());
        int replayGateIndex = names.indexOf(replayGate.name());
        if (packetEventsIndex < 0
                || packetEventsIndex >= paperEncoderIndex
                || paperEncoderIndex >= replayGateIndex) {
            throw new IllegalStateException(
                    "expected PacketEvents encoder before Paper encoder before replay gate");
        }
        return packetEvents;
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            buffer.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer.writeByte(remaining);
    }

    private static int varIntSize(int value) {
        int size = 1;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            size++;
            remaining >>>= 7;
        }
        return size;
    }

    private void report(Throwable failure) {
        try {
            failureHandler.accept(Objects.requireNonNullElseGet(
                    failure,
                    () -> new IllegalStateException("unknown replay channel failure")));
        } catch (Throwable ignored) {
            // Failure reporting must never escape into the connection event loop.
        }
    }
}
