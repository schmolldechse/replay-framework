package dev.voldechse.replayframework.adapter.paper.channel;

import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Writes encoded replay packets to one viewer connection.
 *
 * <p>An anchor handler sits directly behind {@link PipelineNames#COMPRESS}, or
 * behind {@link PipelineNames#PREPENDER} while compression is off. Bytes
 * written from the anchor pass only compression, length framing and
 * encryption. They skip the server's encoder, every handler other plugins
 * placed around it, the viewer gate and the clientbound tap, so replay
 * packets are neither changed, suppressed nor recorded again.</p>
 *
 * <p>Writes and flushes may come from any thread; they run on the
 * connection's event loop in the order they were made.</p>
 */
public final class ReplayChannelWriter {

    private final Channel channel;
    private final Consumer<Throwable> failures;

    /**
     * @param channel the viewer's connection
     * @param failures receives write failures
     */
    public ReplayChannelWriter(Channel channel, Consumer<Throwable> failures) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    /** Adds the anchor; runs on the event loop before any write made afterwards. */
    public void install() {
        onEventLoop(() -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(PipelineNames.WRITE_ANCHOR) != null) {
                return;
            }
            String behind = pipeline.get(PipelineNames.COMPRESS) != null
                    ? PipelineNames.COMPRESS
                    : PipelineNames.PREPENDER;
            if (pipeline.get(behind) == null) {
                throw new IllegalStateException("viewer connection has no " + behind + " handler");
            }
            pipeline.addAfter(behind, PipelineNames.WRITE_ANCHOR, new Anchor());
        });
    }

    /** Removes the anchor after every write made before. */
    public void uninstall() {
        onEventLoop(() -> {
            if (channel.pipeline().get(PipelineNames.WRITE_ANCHOR) != null) {
                channel.pipeline().remove(PipelineNames.WRITE_ANCHOR);
            }
        });
    }

    /** Writes one encoded packet: its VarInt ID followed by its payload. */
    public void write(PacketCodec.EncodedPacket packet) {
        Objects.requireNonNull(packet, "packet");
        onEventLoop(() -> {
            ChannelHandlerContext anchor = anchor();
            ByteBuf buffer = channel.alloc().buffer(varIntSize(packet.packetId()) + packet.payload().length);
            try {
                writeVarInt(buffer, packet.packetId());
                buffer.writeBytes(packet.payload());
                ChannelPromise promise = anchor.newPromise();
                promise.addListener(future -> {
                    if (!future.isSuccess()) {
                        report(future.cause());
                    }
                });
                anchor.write(buffer, promise);
                buffer = null;
            } finally {
                ReferenceCountUtil.release(buffer);
            }
        });
    }

    /** Flushes everything written so far. */
    public void flush() {
        onEventLoop(() -> anchor().flush());
    }

    private ChannelHandlerContext anchor() {
        ChannelHandlerContext anchor = channel.pipeline().context(PipelineNames.WRITE_ANCHOR);
        if (anchor == null) {
            throw new IllegalStateException("replay write anchor is not installed");
        }
        return anchor;
    }

    private void onEventLoop(Runnable action) {
        if (channel.eventLoop().inEventLoop()) {
            run(action);
            return;
        }
        try {
            channel.eventLoop().execute(() -> run(action));
        } catch (Throwable failure) {
            report(failure);
        }
    }

    private void run(Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            report(failure);
        }
    }

    private void report(Throwable failure) {
        try {
            failures.accept(Objects.requireNonNullElseGet(
                    failure, () -> new IllegalStateException("unknown replay channel failure")));
        } catch (Throwable ignored) {
            // Failure reporting must never escape into the connection's event loop.
        }
    }

    static void writeVarInt(ByteBuf buffer, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            buffer.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer.writeByte(remaining);
    }

    static int varIntSize(int value) {
        int size = 1;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            size++;
            remaining >>>= 7;
        }
        return size;
    }

    /** Marks where replay bytes enter the pipeline; passes everything else on unchanged. */
    static final class Anchor extends ChannelOutboundHandlerAdapter {
    }
}
