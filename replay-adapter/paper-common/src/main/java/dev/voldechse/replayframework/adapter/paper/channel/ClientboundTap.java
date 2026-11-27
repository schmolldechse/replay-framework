package dev.voldechse.replayframework.adapter.paper.channel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Sees every clientbound packet object one player connection is sent.
 *
 * <p>The tap sits directly before {@link PipelineNames#PACKET_HANDLER}, the
 * tail of the pipeline, where every packet the server writes enters. It
 * hands each packet object to a listener and passes it on unchanged; it
 * never changes, drops or delays a packet. A bundle is handed over as its
 * packets, the way the client receives them. Bytes another component writes
 * into the pipeline are not handed over. The server also passes other
 * messages through the pipeline; the listener receives them and must ignore
 * everything that is not a packet of its version.</p>
 *
 * <p>Packets are seen before they are encoded, so a listener reads the
 * object itself. Replay packets are written closer to the head of the
 * pipeline and never pass the tap.</p>
 */
public final class ClientboundTap extends ChannelDuplexHandler {

    private final Function<Object, List<Object>> unbundle;
    private final Consumer<Object> listener;
    private final Runnable closed;
    private final Consumer<Throwable> failures;

    /**
     * @param unbundle the packets of a bundle, or the packet itself
     * @param listener receives every packet object on the connection's event loop
     * @param closed runs once when the connection closes
     * @param failures receives listener failures; the packet passes on regardless
     */
    public ClientboundTap(
            Function<Object, List<Object>> unbundle,
            Consumer<Object> listener,
            Runnable closed,
            Consumer<Throwable> failures) {
        this.unbundle = Objects.requireNonNull(unbundle, "unbundle");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.closed = Objects.requireNonNull(closed, "closed");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    /** Adds the tap to a pipeline once; does nothing when it is there or the pipeline has no server connection. */
    public static void install(ChannelPipeline pipeline, ClientboundTap tap) {
        if (pipeline.get(PipelineNames.CLIENTBOUND_TAP) == null
                && pipeline.get(PipelineNames.PACKET_HANDLER) != null) {
            pipeline.addBefore(PipelineNames.PACKET_HANDLER, PipelineNames.CLIENTBOUND_TAP, tap);
        }
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        if (!(message instanceof ByteBuf)) {
            try {
                for (Object packet : unbundle.apply(message)) {
                    listener.accept(packet);
                }
            } catch (Throwable failure) {
                failures.accept(failure);
            }
        }
        super.write(context, message, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        try {
            closed.run();
        } catch (Throwable failure) {
            failures.accept(failure);
        }
        super.channelInactive(context);
    }
}
