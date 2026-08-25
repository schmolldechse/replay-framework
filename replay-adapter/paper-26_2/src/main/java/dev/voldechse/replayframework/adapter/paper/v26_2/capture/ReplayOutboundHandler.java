package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.Objects;
import java.util.function.LongToIntFunction;
import net.minecraft.network.protocol.Packet;

/**
 * Observes one Paper channel before transport framing, compression and
 * encryption while forwarding the original outbound object exactly once.
 */
final class ReplayOutboundHandler extends ChannelOutboundHandlerAdapter {

    private final PaperConnectionAccessor accessor;
    private final PaperConnectionAccessor.ConnectionHandle connection;
    private final PacketRegistry registry;
    private final CaptureBridge.PacketSink sink;
    private final CaptureBridge.FailureHandler failureHandler;
    private final LongToIntFunction sequenceAllocator;

    ReplayOutboundHandler(
            PaperConnectionAccessor accessor,
            PaperConnectionAccessor.ConnectionHandle connection,
            PacketRegistry registry,
            CaptureBridge.PacketSink sink,
            CaptureBridge.FailureHandler failureHandler,
            LongToIntFunction sequenceAllocator) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.sequenceAllocator = Objects.requireNonNull(sequenceAllocator, "sequenceAllocator");
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        if (!(message instanceof Packet<?>)) {
            context.write(message, promise);
            return;
        }

        try {
            PaperConnectionAccessor.WirePacket wirePacket =
                    accessor.inspectAndEncode(connection, message, registry);
            if (wirePacket.phase() != PacketPhase.PLAY) {
                return;
            }
            if (wirePacket.direction() != PacketDescriptor.Direction.CLIENTBOUND) {
                throw incompatible("Paper outbound packet is not clientbound", null);
            }

            PacketDescriptor descriptor = registry.find(
                            wirePacket.phase(), wirePacket.direction(), wirePacket.packetId())
                    .orElseThrow(() -> incompatible(
                            "Paper PLAY outbound packet is not in the verified registry", null));
            if (descriptor.disposition() == PacketDisposition.CONTROL
                    || descriptor.disposition() == PacketDisposition.UNSUPPORTED
                    || !registry.captureAllowed(
                            wirePacket.phase(), wirePacket.direction(), wirePacket.packetId())) {
                return;
            }

            long serverTick = accessor.currentServerTick();
            int sequence = sequenceAllocator.applyAsInt(serverTick);
            sink.accept(new CaptureBridge.CapturePacket(
                    connection.recipientId(),
                    System.nanoTime(),
                    serverTick,
                    sequence,
                    wirePacket.phase(),
                    wirePacket.packetId(),
                    wirePacket.payload()));
        } catch (Throwable failure) {
            reportFailure(failure);
        } finally {
            // Capture is observational. Even a codec, sequence or sink error
            // must not suppress the live packet or write it a second time.
            context.write(message, promise);
        }
    }

    /** Reports an asynchronous installation failure through the same bridge path. */
    void installationFailed(Throwable failure) {
        reportFailure(failure);
    }

    private void reportFailure(Throwable failure) {
        try {
            failureHandler.onFailure(Objects.requireNonNull(failure, "failure"));
        } catch (Throwable ignored) {
            // Failure reporting is diagnostic/control flow and must never escape
            // the Netty event loop into Paper's live write path.
        }
    }

    private static IncompatibleAdapterException incompatible(String message, Throwable cause) {
        return cause == null
                ? new IncompatibleAdapterException(message)
                : new IncompatibleAdapterException(message, cause);
    }
}
