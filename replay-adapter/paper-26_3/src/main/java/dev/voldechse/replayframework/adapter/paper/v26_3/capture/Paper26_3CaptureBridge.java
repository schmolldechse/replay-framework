package dev.voldechse.replayframework.adapter.paper.v26_3.capture;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_3.Paper26_3PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_3.capture.Paper26_3SyntheticStateCollector.SyntheticStatePacket;
import dev.voldechse.replayframework.adapter.paper.v26_3.checkpoint.Paper26_3CheckpointEncoder;
import dev.voldechse.replayframework.adapter.paper.v26_3.checkpoint.Paper26_3NativePacketCodec;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Global Paper 26.3 capture installation shared by all active recording
 * sessions. Session filtering remains in the core router.
 */
public final class Paper26_3CaptureBridge implements CaptureBridge {

    private final PaperConnectionAccessor accessor;
    private final PacketRegistry registry;
    private final Paper26_3EntityStateCapture entityStateCapture;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final Set<Paper26_3SyntheticStateCollector> syntheticCollectors =
            ConcurrentHashMap.newKeySet();
    private final Object handlerStateLock = new Object();
    private final Object sequenceLock = new Object();
    private final PacketListener packetListener = new PacketListener() {
        @Override
        public void onPacketSend(PacketSendEvent event) {
            capture(event);
        }
    };

    private volatile CaptureBridge.PacketSink sink;
    private volatile CaptureBridge.FailureHandler failureHandler;
    private volatile PacketListenerCommon installedListener;
    private long sequenceTick = -1L;
    private int nextSequence;

    /** Creates a bridge and discovers the live Paper 26.3 registry. */
    public Paper26_3CaptureBridge(PaperConnectionAccessor accessor) {
        this(accessor, Paper26_3PacketRegistry.discover());
    }

    /**
     * Creates a bridge using an already verified Paper registry.
     *
     * @param accessor Paper connection boundary
     * @param registry registry published by the Paper 26.3 adapter
     */
    public Paper26_3CaptureBridge(PaperConnectionAccessor accessor, PacketRegistry registry) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.registry = verifyRegistry(registry);
        this.entityStateCapture = null;
    }

    /** Creates the production bridge with recipient-independent entity movement capture. */
    public Paper26_3CaptureBridge(
            JavaPlugin owner,
            PaperConnectionAccessor accessor,
            PacketRegistry registry,
            Paper26_3NativePacketCodec codec) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.registry = verifyRegistry(registry);
        this.entityStateCapture = new Paper26_3EntityStateCapture(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(codec, "codec"),
                this::hasActiveRecording,
                accessor::currentServerTick,
                this::captureSyntheticMovement,
                this::fail);
    }

    @Override
    public void install(PacketSink sink, FailureHandler failureHandler) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(failureHandler, "failureHandler");

        synchronized (handlerStateLock) {
            State current = state.get();
            if (current == State.INSTALLED) {
                if (this.sink == sink && this.failureHandler == failureHandler) {
                    return;
                }
                throw new IllegalStateException("Paper capture bridge is already installed");
            }
            if (current != State.NEW) {
                throw new IllegalStateException("Paper capture bridge cannot be installed in state " + current);
            }
            this.sink = sink;
            this.failureHandler = failureHandler;
            state.set(State.INSTALLED);
        }

        try {
            installedListener = PacketEvents.getAPI().getEventManager().registerListener(
                    packetListener, PacketListenerPriority.MONITOR);
            if (entityStateCapture != null) {
                entityStateCapture.install();
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    @Override
    public void uninstall() {
        State previous;
        synchronized (handlerStateLock) {
            previous = state.getAndSet(State.UNINSTALLED);
        }
        if (previous == State.UNINSTALLED) {
            return;
        }

        // UNINSTALLED is an expected lifecycle end, not an integrity failure.
        unregisterPacketListener();
        closeEntityStateCapture();
        closeSyntheticCollectors();
    }

    @Override
    public boolean installed() {
        return state.get() == State.INSTALLED;
    }

    /**
     * Routes one adapter-generated state packet through the same sink and
     * sequence allocator as a real outbound packet.
     *
     * <p>This is the only bridge entry point for synthetic Paper state. It
     * deliberately has no connection or recipient argument, so generated
     * state remains part of the neutral recording stream.</p>
     */
    public void captureSynthetic(SyntheticStatePacket packet) {
        Objects.requireNonNull(packet, "packet");
        if (state.get() != State.INSTALLED) {
            throw new IllegalStateException("Paper capture bridge is not installed");
        }
        if (packet.phase() != PacketPhase.PLAY) {
            throw incompatible("synthetic Paper packet is not in PLAY phase", null);
        }
        PacketDescriptor descriptor = registry.find(
                        packet.phase(),
                        PacketDescriptor.Direction.CLIENTBOUND,
                        packet.packetId())
                .orElseThrow(() -> incompatible(
                        "synthetic Paper packet is not in the verified registry", null));
        if (!registry.captureAllowed(
                packet.phase(), PacketDescriptor.Direction.CLIENTBOUND, packet.packetId())) {
            throw incompatible("synthetic Paper packet is not capture-eligible", null);
        }
        CaptureBridge.PacketSink currentSink = sink;
        if (currentSink == null) {
            throw new IllegalStateException("Paper capture bridge has no packet sink");
        }
        Key world = Key.key(packet.worldKey());
        CaptureBridge.CapturePacket captured = new CaptureBridge.CapturePacket(
                packet.captureTimeNanos(),
                packet.serverTick(),
                allocateSequence(packet.serverTick()),
                packet.phase(),
                packet.packetId(),
                packet.payload(),
                new CaptureContext(
                        descriptor.disposition(),
                        Optional.of(world),
                        Optional.ofNullable(packet.position()),
                        Optional.empty(),
                        Optional.empty()));
        currentSink.accept(captured);
    }

    private void captureSyntheticMovement(
            Paper26_3EntityStateCapture.SyntheticMovement movement) {
        if (state.get() != State.INSTALLED) {
            return;
        }
        CaptureBridge.PacketSink currentSink = sink;
        if (currentSink == null || !currentSink.hasActiveSinks()) {
            return;
        }
        var packet = movement.packet();
        currentSink.accept(new CaptureBridge.CapturePacket(
                movement.captureTimeNanos(),
                movement.serverTick(),
                allocateSequence(movement.serverTick()),
                PacketPhase.PLAY,
                packet.packetId(),
                packet.payload(),
                new CaptureContext(
                        packet.descriptor().disposition(),
                        Optional.of(movement.world()),
                        Optional.of(movement.position()),
                        Optional.empty(),
                        Optional.empty())));
    }

    /**
     * Creates a collector whose accepted synthetic packets are routed through
     * this bridge. The caller owns and closes the returned collector together
     * with the recording session.
     */
    public Paper26_3SyntheticStateCollector syntheticCollector(
            RecordingScope scope,
            Function<CaptureBridge.CapturePacket, Paper26_3SyntheticStateCollector.ObservedStateKey>
                    observationDecoder,
            Function<Paper26_3SyntheticStateCollector.StateDelta,
                    Paper26_3SyntheticStateCollector.SyntheticStatePacket> deltaEncoder,
            dev.voldechse.replayframework.adapter.CheckpointSignalSource checkpointSignals) {
        if (state.get() != State.INSTALLED) {
            throw new IllegalStateException("Paper capture bridge is not installed");
        }
        AtomicReference<Paper26_3SyntheticStateCollector> collectorReference =
                new AtomicReference<>();
        Paper26_3SyntheticStateCollector collector = new Paper26_3SyntheticStateCollector(
                registry,
                scope,
                observationDecoder,
                deltaEncoder,
                checkpointSignals,
                this::captureSynthetic,
                () -> {
                    Paper26_3SyntheticStateCollector current = collectorReference.get();
                    if (current != null) {
                        syntheticCollectors.remove(current);
                    }
                });
        collectorReference.set(collector);
        syntheticCollectors.add(collector);
        return collector;
    }

    private void capture(PacketSendEvent event) {
        if (state.get() != State.INSTALLED || event.isCancelled()) {
            return;
        }
        try {
            PacketPhase phase = phaseOf(event);
            int packetId = event.getPacketId();
            PacketDescriptor descriptor = registry.find(
                            phase, PacketDescriptor.Direction.CLIENTBOUND, packetId)
                    .orElseThrow(() -> incompatible(
                            "PacketEvents clientbound packet is not in the verified registry: "
                                    + phase + ':' + packetId,
                            null));
            if (!registry.captureAllowed(
                    phase, PacketDescriptor.Direction.CLIENTBOUND, packetId)) {
                return;
            }

            byte[] payload = copyPayload(event);
            if (entityStateCapture != null
                    && entityStateCapture.suppressesRecipientMovement(
                            descriptor.typeName(), payload)) {
                return;
            }
            long serverTick = accessor.currentServerTick();
            CaptureBridge.CapturePacket packet = new CaptureBridge.CapturePacket(
                    System.nanoTime(),
                    serverTick,
                    allocateSequence(serverTick),
                    phase,
                    packetId,
                    payload,
                    captureContext(event, descriptor, phase, packetId, payload, serverTick));
            for (Paper26_3SyntheticStateCollector collector : syntheticCollectors) {
                collector.observe(packet);
            }
            CaptureBridge.PacketSink currentSink = sink;
            if (currentSink != null) {
                currentSink.accept(packet);
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private CaptureContext captureContext(
            PacketSendEvent event,
            PacketDescriptor descriptor,
            PacketPhase phase,
            int packetId,
            byte[] payload,
            long serverTick) {
        Object candidate = event.getPlayer();
        if (candidate instanceof Player player) {
            PaperConnectionAccessor.ConnectionHandle connection = accessor.connectionFor(player);
            if (connection != null) {
                if (phase == PacketPhase.PLAY) {
                    Object decoded = accessor.decodeCapturedFrame(
                            connection,
                            new RawPacketFrame(0L, serverTick, 0, phase, packetId, payload),
                            registry);
                    return accessor.captureContext(connection, decoded, descriptor);
                }
                return new CaptureContext(
                        descriptor.disposition(),
                        connection.world(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
            }
        }
        return new CaptureContext(
                descriptor.disposition(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static PacketPhase phaseOf(PacketSendEvent event) {
        return switch (event.getConnectionState().name()) {
            case "STATUS" -> PacketPhase.STATUS;
            case "LOGIN" -> PacketPhase.LOGIN;
            case "CONFIGURATION" -> PacketPhase.CONFIGURATION;
            case "PLAY" -> PacketPhase.PLAY;
            default -> throw incompatible(
                    "PacketEvents exposed an unsupported clientbound connection state "
                            + event.getConnectionState(),
                    null);
        };
    }

    private static byte[] copyPayload(PacketSendEvent event) {
        Object raw = event.getByteBuf();
        if (!(raw instanceof ByteBuf buffer)) {
            throw incompatible("PacketEvents did not expose a Netty byte buffer", null);
        }
        byte[] payload = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), payload);
        return payload;
    }

    private void fail(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        synchronized (handlerStateLock) {
            State current = state.get();
            if (current == State.FAILED || current == State.UNINSTALLED) {
                return;
            }
            // FAILED means a capture-integrity invariant was lost. Active
            // recordings must observe this and cannot continue as available.
            state.set(State.FAILED);
        }

        unregisterPacketListener();
        closeEntityStateCapture();

        if (failureReported.compareAndSet(false, true)) {
            CaptureBridge.FailureHandler currentFailureHandler = failureHandler;
            if (currentFailureHandler != null) {
                try {
                    currentFailureHandler.onFailure(failure);
                } catch (Throwable ignored) {
                    // A diagnostic failure handler must not escape a callback.
                }
            }
        }
    }

    private void unregisterPacketListener() {
        PacketListenerCommon listener = installedListener;
        installedListener = null;
        if (listener == null) {
            return;
        }
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        } catch (Throwable ignored) {
            // PacketEvents may already have stopped while Paper disables plugins.
        }
    }

    private int allocateSequence(long serverTick) {
        if (serverTick < 0L) {
            throw incompatible("Paper server tick is negative", null);
        }
        synchronized (sequenceLock) {
            if (serverTick < sequenceTick) {
                throw incompatible("Paper server tick moved backwards", null);
            }
            if (serverTick != sequenceTick) {
                sequenceTick = serverTick;
                nextSequence = 0;
            }
            if (nextSequence == Integer.MAX_VALUE) {
                throw incompatible("Paper capture sequence overflow", null);
            }
            return nextSequence++;
        }
    }

    private void closeSyntheticCollectors() {
        List<Paper26_3SyntheticStateCollector> collectors =
                new ArrayList<>(syntheticCollectors);
        syntheticCollectors.clear();
        for (Paper26_3SyntheticStateCollector collector : collectors) {
            try {
                collector.close();
            } catch (Throwable ignored) {
                // Capture shutdown remains best effort after the hook is gone.
            }
        }
    }

    private boolean hasActiveRecording() {
        CaptureBridge.PacketSink currentSink = sink;
        return state.get() == State.INSTALLED
                && currentSink != null
                && currentSink.hasActiveSinks();
    }

    private void closeEntityStateCapture() {
        if (entityStateCapture != null) {
            entityStateCapture.close();
        }
    }

    private static PacketRegistry verifyRegistry(PacketRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        if (!(registry instanceof Paper26_3PacketRegistry)) {
            throw incompatible("Paper capture requires the Paper 26.3 packet registry", null);
        }
        String liveFingerprint = Paper26_3PacketRegistry.discover().fingerprint();
        if (!liveFingerprint.equals(registry.fingerprint())) {
            throw incompatible("Paper packet registry fingerprint changed", null);
        }
        return registry;
    }

    private static IncompatibleAdapterException incompatible(String message, Throwable cause) {
        return cause == null
                ? new IncompatibleAdapterException(message)
                : new IncompatibleAdapterException(message, cause);
    }

    /** Status values document whether capture is trusted, failed or shut down. */
    private enum State {
        /** No PacketEvents callback is installed. */
        NEW,
        /** The single PacketEvents callback is active. */
        INSTALLED,
        /** Capture integrity failed; active recording must fail. */
        FAILED,
        /** Expected terminal lifecycle state after uninstall. */
        UNINSTALLED
    }
}
