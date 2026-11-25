package dev.voldechse.replayframework.adapter.paper.playback;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.playback.NeutralObserverPolicy;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.buffer.ByteBuf;
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

/**
 * Keeps live scene packets from one replay viewer while it watches a replay.
 *
 * <p>The gate sits directly behind the server's encoder and sees live packet
 * objects. It classifies each by its definition, without encoding it, and
 * drops what would conflict with the replay scene: chunks, entities, blocks
 * and effects of the viewer's own world. The viewer's own state, its user
 * interface and chat pass. A live {@code respawn} means the viewer changes
 * worlds; since 26.x a client keeps its player frozen until the chunk it
 * stands in has arrived, so the live world passes until the playback
 * rebuilds its view and resumes isolation.</p>
 *
 * <p>Replay packets are written closer to the head of the pipeline and never
 * pass the gate.</p>
 */
public final class ViewerGate extends ChannelOutboundHandlerAdapter {

    /** Pipeline name of the gate. */
    public static final String HANDLER_NAME = "replay-framework-playback-gate";
    private static final String CLIENTBOUND_TYPE_PREFIX = "play.clientbound.";
    private static final String RESPAWN = CLIENTBOUND_TYPE_PREFIX + "minecraft:respawn";

    private final PacketRegistry registry;
    private final Function<Object, Optional<PacketDescriptor>> classify;
    private final Runnable readyHandler;
    private final Consumer<Throwable> failureHandler;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicBoolean isolationActive = new AtomicBoolean();
    private final AtomicBoolean isolationRequested = new AtomicBoolean();
    private volatile ChannelHandlerContext context;

    /**
     * @param registry the verified registry
     * @param classify the PLAY clientbound descriptor of a live packet object,
     *                 empty for anything that is not such a packet
     * @param readyHandler runs once the gate is in the pipeline
     * @param failureHandler receives a failure; the live packet passes regardless
     */
    public ViewerGate(
            PacketRegistry registry,
            Function<Object, Optional<PacketDescriptor>> classify,
            Runnable readyHandler,
            Consumer<Throwable> failureHandler) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.classify = Objects.requireNonNull(classify, "classify");
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
        if (state.get() != State.ACTIVE || message instanceof ByteBuf) {
            context.write(message, promise);
            return;
        }
        try {
            Optional<PacketDescriptor> descriptor = classify.apply(message);
            if (descriptor.isPresent()) {
                if (RESPAWN.equals(descriptor.get().typeName())) {
                    isolationActive.set(false);
                } else if (shouldSuppress(descriptor.get())) {
                    ReferenceCountUtil.release(message);
                    promise.trySuccess();
                    return;
                }
            }
        } catch (Throwable failure) {
            // A live write must remain live-safe. The replay view is failed,
            // but the original message is forwarded exactly once.
            fail(failure);
        }
        context.write(message, promise);
    }

    /** Starts dropping conflicting live scene packets, after the viewer was placed. */
    public void activateIsolation() {
        ChannelHandlerContext current = context;
        if (current == null || state.get() != State.ACTIVE) {
            throw new IllegalStateException("replay viewer gate is not active");
        }
        isolationRequested.set(true);
        if (current.executor().inEventLoop()) {
            isolationActive.set(true);
        } else {
            current.executor().execute(() -> isolationActive.set(true));
        }
    }

    /** Drops conflicting live packets again after a world change; the replay view was rebuilt. */
    public void resumeIsolation() {
        if (isolationRequested.get()) {
            isolationActive.set(true);
        }
    }

    /** Marks the gate closed before it is removed from the pipeline. */
    public void close() {
        state.set(State.CLOSED);
        context = null;
    }

    private boolean shouldSuppress(PacketDescriptor descriptor) {
        if (!isolationActive.get()
                || descriptor.phase() != PacketPhase.PLAY
                || descriptor.direction() != PacketDescriptor.Direction.CLIENTBOUND) {
            return false;
        }
        // The viewer's own state, controls and status UI stay live.
        if (descriptor.playbackScope() == PacketDescriptor.PlaybackScope.VIEWER_LOCAL) {
            return false;
        }
        if (descriptor.typeName().startsWith(CLIENTBOUND_TYPE_PREFIX)
                && NeutralObserverPolicy.isRecordedChatPresentation(
                        descriptor.typeName().substring(CLIENTBOUND_TYPE_PREFIX.length()))) {
            return false;
        }
        return descriptor.replayable()
                && registry.replayAllowed(descriptor.phase(), descriptor.direction(), descriptor.packetId())
                && switch (descriptor.disposition()) {
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

    private enum State {
        NEW,
        ACTIVE,
        FAILED,
        CLOSED
    }
}
