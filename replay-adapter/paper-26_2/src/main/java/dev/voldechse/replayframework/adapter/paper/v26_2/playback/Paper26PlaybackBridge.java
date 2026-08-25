package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.PaperConnectionAccessor;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import org.bukkit.entity.Player;

/** Paper 26.2 implementation of the versions-neutral playback port. */
public final class Paper26PlaybackBridge implements PlaybackBridge {

    private static final String ADAPTER_ID = "paper-26.2";

    private final PaperConnectionAccessor accessor;
    private final PaperConnectionAccessor.ConnectionHandle connection;
    private final PacketRegistry registry;
    private final Consumer<Throwable> failureHandler;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final ReplayViewerOutboundGate gate;
    private final Paper26ViewResetter resetter;

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
        this.registry = Objects.requireNonNull(registry, "registry");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        validateDescriptor(adapterDescriptor, registry);

        PaperConnectionAccessor.ConnectionHandle resolved = accessor.connectionFor(player);
        if (resolved == null) {
            throw new IllegalStateException("Paper playback player has no open connection");
        }
        this.connection = resolved;
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
    public void send(RawPacketFrame frame) {
        Objects.requireNonNull(frame, "frame");
        requireUsable("send");
        validateFrame(frame);

        connection.channel().eventLoop().execute(() -> {
            if (isTerminal()) {
                return;
            }
            try {
                Object decoded = accessor.decodeReplayFrame(connection, frame, registry);
                gate.writeReplay(decoded);
                resetter.observe(decoded);
            } catch (Throwable failure) {
                fail(failure);
            }
        });
    }

    @Override
    public void resetView() {
        requireUsable("resetView");
        state.updateAndGet(current -> switch (current) {
            case NEW, INSTALLING, OPEN -> State.RESETTING;
            case RESETTING -> State.RESETTING;
            case FAILED, CLOSING, CLOSED -> current;
        });

        connection.channel().eventLoop().execute(() -> {
            if (isTerminal()) {
                return;
            }
            try {
                resetter.reset();
                state.compareAndSet(State.RESETTING, State.OPEN);
            } catch (Throwable failure) {
                fail(failure);
            }
        });
    }

    @Override
    public void close() {
        State previous = state.getAndSet(State.CLOSING);
        if (previous == State.CLOSED || previous == State.CLOSING) {
            return;
        }

        gate.close();
        try {
            accessor.uninstallHandler(connection, ReplayViewerOutboundGate.HANDLER_NAME);
        } catch (Throwable ignored) {
            // Channel teardown is best effort during quit and server shutdown.
        }
        state.set(State.CLOSED);
    }

    private void gateReady() {
        state.compareAndSet(State.INSTALLING, State.OPEN);
    }

    private void writeResetPacket(Object packet) {
        if (!(packet instanceof Packet<?>)) {
            throw incompatible("Paper reset packet is not a Minecraft packet", null);
        }
        PaperConnectionAccessor.WirePacket wirePacket = accessor.inspectAndEncode(
                connection,
                packet,
                registry);
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

    private void validateFrame(RawPacketFrame frame) {
        if (frame.phase() != PacketPhase.PLAY) {
            IncompatibleAdapterException failure = incompatible("replay frame is not in PLAY phase", null);
            fail(failure);
            throw failure;
        }
        PacketDescriptor descriptor = registry.find(
                        frame.phase(), PacketDescriptor.Direction.CLIENTBOUND, frame.packetId())
                .orElse(null);
        if (descriptor == null
                || !descriptor.replayable()
                || !registry.replayAllowed(
                        frame.phase(), PacketDescriptor.Direction.CLIENTBOUND, frame.packetId())) {
            IncompatibleAdapterException failure = incompatible(
                    "replay packet ID is not replayable in the verified registry", null);
            fail(failure);
            throw failure;
        }
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
            accessor.uninstallHandler(connection, ReplayViewerOutboundGate.HANDLER_NAME);
        } catch (Throwable ignored) {
            // Failure cleanup is best effort and must not escape Netty.
        }
        state.set(State.CLOSED);

        if (failureReported.compareAndSet(false, true)) {
            try {
                failureHandler.accept(failure);
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
