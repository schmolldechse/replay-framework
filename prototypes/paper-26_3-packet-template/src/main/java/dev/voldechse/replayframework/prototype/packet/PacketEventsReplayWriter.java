package dev.voldechse.replayframework.prototype.packet;

import com.github.retrooper.packetevents.PacketEvents;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Writes encoded replay bytes before PacketEvents so they cannot be recaptured. */
public final class PacketEventsReplayWriter {

    private static final String PAPER_ENCODER_NAME = "encoder";
    private static final String REPLAY_GATE_NAME = "replay-framework-playback-gate";

    private final Channel channel;
    private final Consumer<Throwable> failureHandler;

    public PacketEventsReplayWriter(Channel channel, Consumer<Throwable> failureHandler) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    public void writeAndFlush(EncodedPacket packet) {
        Objects.requireNonNull(packet, "packet");
        if (!channel.eventLoop().inEventLoop()) {
            try {
                channel.eventLoop().execute(() -> writeAndFlush(packet));
            } catch (Throwable failure) {
                report(failure);
            }
            return;
        }

        ByteBuf buffer = null;
        try {
            ChannelHandlerContext packetEventsContext = packetEventsContext(channel.pipeline());
            buffer = channel.alloc().buffer();
            VarInts.write(buffer, packet.packetId());
            buffer.writeBytes(packet.payload());

            ChannelPromise promise = packetEventsContext.newPromise();
            promise.addListener(future -> {
                if (!future.isSuccess()) {
                    report(future.cause());
                }
            });

            // Outbound traversal continues at the handler before PacketEvents.
            // Paper's encoder, PacketEvents and the later replay gate are skipped.
            packetEventsContext.write(buffer, promise);
            buffer = null; // ownership transferred to the pipeline
            packetEventsContext.flush();
        } catch (Throwable failure) {
            report(failure);
        } finally {
            ReferenceCountUtil.release(buffer);
        }
    }

    private static ChannelHandlerContext packetEventsContext(ChannelPipeline pipeline) {
        ChannelHandlerContext packetEvents = pipeline.context(PacketEvents.ENCODER_NAME);
        ChannelHandlerContext paperEncoder = pipeline.context(PAPER_ENCODER_NAME);
        if (packetEvents == null || paperEncoder == null) {
            throw new IllegalStateException("PacketEvents or Paper encoder is missing");
        }

        List<String> names = pipeline.names();
        int packetEventsIndex = names.indexOf(packetEvents.name());
        int paperEncoderIndex = names.indexOf(paperEncoder.name());
        int replayGateIndex = names.indexOf(REPLAY_GATE_NAME);
        if (packetEventsIndex < 0 || paperEncoderIndex < 0
                || packetEventsIndex >= paperEncoderIndex) {
            throw new IllegalStateException(
                    "PacketEvents encoder must precede the Paper encoder");
        }
        if (replayGateIndex >= 0 && paperEncoderIndex >= replayGateIndex) {
            throw new IllegalStateException(
                    "replay gate must follow the Paper encoder");
        }
        return packetEvents;
    }

    private void report(Throwable failure) {
        try {
            failureHandler.accept(Objects.requireNonNullElseGet(
                    failure,
                    () -> new IllegalStateException("unknown replay channel failure")));
        } catch (Throwable ignored) {
            // Diagnostics must not escape into the player's event loop.
        }
    }
}
