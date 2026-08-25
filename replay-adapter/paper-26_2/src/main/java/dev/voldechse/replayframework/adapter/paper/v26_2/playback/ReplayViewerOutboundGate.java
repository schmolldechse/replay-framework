package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.PaperConnectionAccessor;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.network.protocol.Packet;

/**
 * Channel-local outbound gate for one replay viewer. Live packets are
 * classified before they reach the capture observer; replay packets are
 * written from this handler's context and therefore travel only through the
 * preceding Paper transport stages.
 */
final class ReplayViewerOutboundGate extends ChannelOutboundHandlerAdapter {

    static final String HANDLER_NAME = "replay-framework-playback-gate";

    private final PacketRegistry registry;
    private final Function<Object, PaperConnectionAccessor.WirePacket> inspector;
    private final Runnable readyHandler;
    private final Consumer<Throwable> failureHandler;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private volatile ChannelHandlerContext context;

    ReplayViewerOutboundGate(
            PaperConnectionAccessor accessor,
            PaperConnectionAccessor.ConnectionHandle connection,
            PacketRegistry registry,
            Runnable readyHandler,
            Consumer<Throwable> failureHandler) {
        this(
                registry,
                message -> accessor.inspectAndEncode(connection, message, registry),
                readyHandler,
                failureHandler);
    }

    /** Constructor kept package-private for focused EmbeddedChannel checks. */
    ReplayViewerOutboundGate(
            PacketRegistry registry,
            Function<Object, PaperConnectionAccessor.WirePacket> inspector,
            Consumer<Throwable> failureHandler) {
        this(registry, inspector, () -> {}, failureHandler);
    }

    private ReplayViewerOutboundGate(
            PacketRegistry registry,
            Function<Object, PaperConnectionAccessor.WirePacket> inspector,
            Runnable readyHandler,
            Consumer<Throwable> failureHandler) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.readyHandler = Objects.requireNonNull(readyHandler, "readyHandler");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) throws Exception {
        this.context = Objects.requireNonNull(context, "context");
        if (!state.compareAndSet(State.NEW, State.ACTIVE)) {
            throw new IllegalStateException("replay viewer gate was added more than once");
        }
        try {
            readyHandler.run();
        } catch (Throwable failure) {
            fail(failure);
            throw failure;
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) throws Exception {
        this.context = null;
        state.set(State.CLOSED);
        super.handlerRemoved(context);
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        if (state.get() != State.ACTIVE || !(message instanceof Packet<?>)) {
            context.write(message, promise);
            return;
        }

        try {
            PaperConnectionAccessor.WirePacket wirePacket = inspector.apply(message);
            if (shouldSuppress(wirePacket)) {
                ReferenceCountUtil.release(message);
                promise.trySuccess();
                return;
            }
        } catch (Throwable failure) {
            // A live write must remain live-safe. The replay view is failed,
            // but the original message is forwarded exactly once.
            fail(failure);
        }

        context.write(message, promise);
    }

    /**
     * Writes a decoded replay packet through the handlers before this gate.
     * Calling {@code context.write} is intentional: it skips this gate and
     * the later capture observer but still reaches the Paper encoder.
     */
    void writeReplay(Object decodedPacket) {
        Objects.requireNonNull(decodedPacket, "decodedPacket");
        ChannelHandlerContext current = context;
        if (current == null || state.get() != State.ACTIVE) {
            throw new IllegalStateException("replay viewer gate is not active");
        }
        if (!current.executor().inEventLoop()) {
            current.executor().execute(() -> {
                try {
                    writeReplay(decodedPacket);
                } catch (Throwable failure) {
                    ReferenceCountUtil.release(decodedPacket);
                    fail(failure);
                }
            });
            return;
        }
        current.write(decodedPacket, current.newPromise());
    }

    /** Marks the gate closed before the accessor removes the pipeline entry. */
    void close() {
        state.set(State.CLOSED);
        context = null;
    }

    private boolean shouldSuppress(PaperConnectionAccessor.WirePacket wirePacket) {
        if (wirePacket.phase() != PacketPhase.PLAY
                || wirePacket.direction() != PacketDescriptor.Direction.CLIENTBOUND) {
            return false;
        }
        Optional<PacketDescriptor> descriptor = registry.find(
                wirePacket.phase(), wirePacket.direction(), wirePacket.packetId());
        if (descriptor.isEmpty()) {
            return false;
        }
        PacketDescriptor value = descriptor.orElseThrow();
        return value.replayable()
                && registry.replayAllowed(
                        wirePacket.phase(), wirePacket.direction(), wirePacket.packetId())
                && switch (value.disposition()) {
                    case STATEFUL, EPHEMERAL, CONFIGURABLE -> true;
                    case CONTROL, UNSUPPORTED -> false;
                };
    }

    private void fail(Throwable failure) {
        state.set(State.FAILED);
        if (!failureReported.compareAndSet(false, true)) {
            return;
        }
        try {
            failureHandler.accept(Objects.requireNonNull(failure, "failure"));
        } catch (Throwable ignored) {
            // Playback diagnostics must never terminate the live Netty path.
        }
    }

    /** Gate states are channel-local and are mutated on the channel event loop. */
    private enum State {
        NEW,
        ACTIVE,
        FAILED,
        CLOSED
    }
}
