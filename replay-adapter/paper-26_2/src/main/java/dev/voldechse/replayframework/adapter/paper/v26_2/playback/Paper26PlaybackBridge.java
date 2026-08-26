package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.PaperConnectionAccessor;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import org.bukkit.entity.Player;

/** Paper 26.2 implementation of the versions-neutral playback port. */
public final class Paper26PlaybackBridge implements PlaybackBridge {

    private static final String ADAPTER_ID = "paper-26.2";
    private static final int MAX_OUTBOUND_OPERATIONS_PER_TURN = 128;
    private static final long OUTBOUND_BATCH_DELAY_MILLIS = 1L;

    private final PaperConnectionAccessor accessor;
    private final PaperConnectionAccessor.ConnectionHandle connection;
    private final String observerName;
    private final int observerEntityId;
    private final PacketRegistry registry;
    private final PacketCodec<Object> codec;
    private final AtomicReference<Consumer<Throwable>> failureHandler;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final ReplayViewerOutboundGate gate;
    private final Paper26ViewResetter resetter;
    private final AtomicReference<Paper26ReplayPacketRewriter> rewriter =
            new AtomicReference<>();
    private final Object outboundLock = new Object();
    private final ArrayDeque<Runnable> outboundOperations = new ArrayDeque<>();
    private final ArrayDeque<CompletableFuture<Void>> outboundIdleWaiters = new ArrayDeque<>();
    private boolean outboundDrainScheduled;

    /**
     * Opens one channel-local playback boundary and schedules gate
     * installation on the channel event loop.
     */
    public Paper26PlaybackBridge(
            PaperConnectionAccessor accessor,
            Player player,
            AdapterDescriptor adapterDescriptor,
            PacketRegistry registry,
            Consumer<Throwable> failureHandler) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        Objects.requireNonNull(player, "player");
        this.observerName = player.getName();
        this.observerEntityId = player.getEntityId();
        this.registry = Objects.requireNonNull(registry, "registry");
        this.failureHandler = new AtomicReference<>(
                Objects.requireNonNull(failureHandler, "failureHandler"));
        validateDescriptor(adapterDescriptor, registry);

        PaperConnectionAccessor.ConnectionHandle resolved = accessor.connectionFor(player);
        if (resolved == null) {
            throw new IllegalStateException("Paper playback player has no open connection");
        }
        this.connection = resolved;
        this.codec = new Paper26PacketCodec(accessor, connection, registry);
        this.resetter = new Paper26ViewResetter(registry, this::writeResetPacket, this::fail);
        this.gate = new ReplayViewerOutboundGate(
                accessor,
                connection,
                registry,
                this::gateReady,
                this::fail);
        state.set(State.INSTALLING);
        try {
            accessor.installPlaybackGate(
                    connection,
                    ReplayViewerOutboundGate.HANDLER_NAME,
                    gate,
                    this::fail);
        } catch (Throwable failure) {
            fail(failure);
            throw failureAsRuntime(failure);
        }
    }

    @Override
    public void setIdentityContext(PlaybackIdentityContext context) {
        Objects.requireNonNull(context, "context");
        requireUsable("setIdentityContext");
        if (!rewriter.compareAndSet(
                null, new Paper26ReplayPacketRewriter(
                        registry,
                        context,
                        Set.of(context.observerId()),
                        Set.of(observerEntityId),
                        Set.of(observerName)))) {
            throw new IllegalStateException("Paper playback identity context was already bound");
        }
    }

    @Override
    public void setFailureHandler(Consumer<Throwable> failureHandler) {
        Objects.requireNonNull(failureHandler, "failureHandler");
        if (isTerminal()) {
            throw new IllegalStateException("Paper playback bridge is already closed");
        }
        this.failureHandler.set(failureHandler);
    }

    @Override
    public void send(RawPacketFrame frame) {
        Objects.requireNonNull(frame, "frame");
        requireUsable("send");
        if (!shouldReplay(frame)) {
            return;
        }

        enqueueOutbound(() -> processFrame(frame));
    }

    @Override
    public void resetView() {
        requireUsable("resetView");
        state.updateAndGet(current -> switch (current) {
            case NEW, INSTALLING, OPEN -> State.RESETTING;
            case RESETTING -> State.RESETTING;
            case FAILED, CLOSING, CLOSED -> current;
        });

        enqueueOutbound(() -> {
            resetter.reset();
            Paper26ReplayPacketRewriter currentRewriter = rewriter.get();
            if (currentRewriter != null) {
                currentRewriter.resetView();
            }
            state.compareAndSet(State.RESETTING, State.OPEN);
        });
    }

    @Override
    public CompletionStage<Void> awaitOutboundIdle() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (outboundLock) {
            if (isTerminal()) {
                result.completeExceptionally(
                        new IllegalStateException("Paper playback bridge is already closed"));
            } else if (outboundOperations.isEmpty() && !outboundDrainScheduled) {
                result.complete(null);
            } else {
                outboundIdleWaiters.addLast(result);
            }
        }
        return result;
    }

    @Override
    public void discardQueuedReplayPackets() {
        requireUsable("discardQueuedReplayPackets");
        clearOutboundOperations(new CancellationException(
                "Paper playback bridge outbound queue was superseded by a seek"));
    }

    @Override
    public void close() {
        State previous = state.getAndSet(State.CLOSING);
        if (previous == State.CLOSED || previous == State.CLOSING) {
            return;
        }

        clearOutboundOperations();
        gate.close();
        Paper26ReplayPacketRewriter currentRewriter = rewriter.getAndSet(null);
        if (currentRewriter != null) {
            currentRewriter.clear();
        }
        try {
            accessor.uninstallHandlerAndWait(connection, ReplayViewerOutboundGate.HANDLER_NAME);
        } catch (Throwable ignored) {
            // Channel teardown is best effort during quit and server shutdown.
        }
        state.set(State.CLOSED);
    }

    private void gateReady() {
        state.compareAndSet(State.INSTALLING, State.OPEN);
    }

    private void enqueueOutbound(Runnable operation) {
        boolean scheduleDrain;
        synchronized (outboundLock) {
            if (isTerminal()) {
                return;
            }
            outboundOperations.addLast(operation);
            scheduleDrain = !outboundDrainScheduled;
            if (scheduleDrain) {
                outboundDrainScheduled = true;
            }
        }
        if (!scheduleDrain) {
            return;
        }
        try {
            connection.channel().eventLoop().execute(this::drainOutbound);
        } catch (Throwable failure) {
            clearOutboundOperations();
            fail(failure);
        }
    }

    private void drainOutbound() {
        int processed = 0;
        while (processed < MAX_OUTBOUND_OPERATIONS_PER_TURN) {
            Runnable operation;
            synchronized (outboundLock) {
                if (isTerminal()) {
                    outboundOperations.clear();
                    outboundDrainScheduled = false;
                    completeOutboundWaiters(new IllegalStateException(
                            "Paper playback bridge is no longer active"));
                    return;
                }
                operation = outboundOperations.pollFirst();
            }
            if (operation == null) {
                boolean finished;
                synchronized (outboundLock) {
                    finished = outboundOperations.isEmpty() || isTerminal();
                    if (finished) {
                        outboundOperations.clear();
                        outboundDrainScheduled = false;
                    }
                }
                if (finished) {
                    try {
                        flushOutboundBatch(processed);
                        completeOutboundWaiters(null);
                    } catch (Throwable failure) {
                        clearOutboundOperations();
                        fail(failure);
                    }
                    return;
                }
                continue;
            }
            try {
                operation.run();
            } catch (Throwable failure) {
                clearOutboundOperations();
                fail(failure);
                return;
            }
            processed++;
        }

        try {
            flushOutboundBatch(processed);
        } catch (Throwable failure) {
            clearOutboundOperations();
            fail(failure);
            return;
        }

        synchronized (outboundLock) {
            if (outboundOperations.isEmpty() || isTerminal()) {
                outboundOperations.clear();
                outboundDrainScheduled = false;
                completeOutboundWaiters(isTerminal()
                        ? new IllegalStateException("Paper playback bridge is no longer active")
                        : null);
                return;
            }
        }
        try {
            connection.channel().eventLoop().schedule(
                    this::drainOutbound,
                    OUTBOUND_BATCH_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            clearOutboundOperations();
            fail(failure);
        }
    }

    private void flushOutboundBatch(int processed) {
        if (processed > 0) {
            gate.flushReplay();
        }
    }

    private void processFrame(RawPacketFrame frame) {
        if (isTerminal()) {
            return;
        }
        Paper26ReplayPacketRewriter currentRewriter = rewriter.get();
        if (currentRewriter == null) {
            throw incompatible("Paper playback identity context is not bound", null);
        }
        Object decoded = codec.decode(frame);
        PacketDescriptor descriptor = registry.find(
                        frame.phase(), PacketDescriptor.Direction.CLIENTBOUND, frame.packetId())
                .orElseThrow(() -> incompatible(
                        "replay packet descriptor disappeared from the verified registry", null));
        Paper26ReplayPacketRewriter.RewriteResult result = currentRewriter.rewrite(
                decoded, descriptor);
        if (result.decision() == Paper26ReplayPacketRewriter.RewriteResult.Decision.FAIL) {
            throw incompatible("replay packet could not be isolated: " + result.reason(), null);
        }
        if (result.decision() == Paper26ReplayPacketRewriter.RewriteResult.Decision.SEND) {
            gate.writeReplay(result.packet());
            resetter.observe(result.packet());
        }
    }

    private void clearOutboundOperations() {
        clearOutboundOperations(new IllegalStateException(
                "Paper playback bridge outbound queue was cleared"));
    }

    private void clearOutboundOperations(Throwable failure) {
        ArrayDeque<CompletableFuture<Void>> waiters;
        synchronized (outboundLock) {
            outboundOperations.clear();
            outboundDrainScheduled = false;
            waiters = new ArrayDeque<>(outboundIdleWaiters);
            outboundIdleWaiters.clear();
        }
        for (CompletableFuture<Void> waiter : waiters) {
            waiter.completeExceptionally(failure);
        }
    }

    private void completeOutboundWaiters(Throwable failure) {
        ArrayDeque<CompletableFuture<Void>> waiters;
        synchronized (outboundLock) {
            if (failure == null && (!outboundOperations.isEmpty() || outboundDrainScheduled)) {
                return;
            }
            waiters = new ArrayDeque<>(outboundIdleWaiters);
            outboundIdleWaiters.clear();
        }
        for (CompletableFuture<Void> waiter : waiters) {
            if (failure == null) {
                waiter.complete(null);
            } else {
                waiter.completeExceptionally(failure);
            }
        }
    }

    private void writeResetPacket(Object packet) {
        if (!(packet instanceof Packet<?>)) {
            throw incompatible("Paper reset packet is not a Minecraft packet", null);
        }
        PacketCodec.EncodedPacket wirePacket = codec.encode(packet);
        if (wirePacket.phase() != PacketPhase.PLAY
                || wirePacket.direction() != PacketDescriptor.Direction.CLIENTBOUND) {
            throw incompatible("Paper reset packet is not PLAY clientbound", null);
        }
        PacketDescriptor descriptor = registry.find(
                        wirePacket.phase(), wirePacket.direction(), wirePacket.packetId())
                .orElseThrow(() -> incompatible("Paper reset packet is not in the verified registry", null));
        if (!descriptor.replayable()
                || !registry.replayAllowed(
                        wirePacket.phase(), wirePacket.direction(), wirePacket.packetId())) {
            throw incompatible("Paper reset packet is blocked by the verified registry", null);
        }
        gate.writeReplay(packet);
    }

    /** Drops every recorded packet that has no explicit safe replay contract. */
    private boolean shouldReplay(RawPacketFrame frame) {
        if (frame.phase() != PacketPhase.PLAY) {
            return false;
        }
        PacketDescriptor descriptor = registry.find(
                        frame.phase(), PacketDescriptor.Direction.CLIENTBOUND, frame.packetId())
                .orElse(null);
        return descriptor != null
                && descriptor.replayable()
                && registry.replayAllowed(
                        frame.phase(), PacketDescriptor.Direction.CLIENTBOUND, frame.packetId());
    }

    private void requireUsable(String operation) {
        State current = state.get();
        if (current == State.FAILED || current == State.CLOSING || current == State.CLOSED) {
            throw new IllegalStateException("Paper playback bridge cannot " + operation + " in state " + current);
        }
    }

    private boolean isTerminal() {
        State current = state.get();
        return current == State.FAILED || current == State.CLOSING || current == State.CLOSED;
    }

    private void fail(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        State current = state.getAndSet(State.FAILED);
        if (current == State.CLOSED || current == State.CLOSING) {
            return;
        }

        gate.close();
        try {
            accessor.uninstallHandlerAndWait(connection, ReplayViewerOutboundGate.HANDLER_NAME);
        } catch (Throwable ignored) {
            // Failure cleanup is best effort and must not escape Netty.
        }
        state.set(State.CLOSED);

        if (failureReported.compareAndSet(false, true)) {
            try {
                failureHandler.get().accept(failure);
            } catch (Throwable ignored) {
                // A diagnostic callback must not terminate the caller or loop.
            }
        }
    }

    private static void validateDescriptor(
            AdapterDescriptor descriptor,
            PacketRegistry registry) {
        Objects.requireNonNull(descriptor, "adapterDescriptor");
        if (!ADAPTER_ID.equals(descriptor.adapterId())) {
            throw incompatible("unsupported playback adapter " + descriptor.adapterId(), null);
        }
        if (!descriptor.registryFingerprint().equals(registry.fingerprint())) {
            throw incompatible("playback registry fingerprint does not match adapter descriptor", null);
        }
    }

    private static IncompatibleAdapterException incompatible(String message, Throwable cause) {
        return cause == null
                ? new IncompatibleAdapterException(message)
                : new IncompatibleAdapterException(message, cause);
    }

    private static RuntimeException failureAsRuntime(Throwable failure) {
        return failure instanceof RuntimeException runtime
                ? runtime
                : new IllegalStateException("Paper playback gate installation failed", failure);
    }

    /** Status values document bridge trust and expected lifecycle transitions. */
    private enum State {
        NEW,
        INSTALLING,
        OPEN,
        RESETTING,
        FAILED,
        CLOSING,
        CLOSED
    }
}
