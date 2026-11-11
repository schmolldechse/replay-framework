package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.Paper26_2SyntheticStateCollector.SyntheticStatePacket;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2CheckpointEncoder;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2NativePacketCodec;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Global Paper 26.2 capture installation shared by all active recording
 * sessions. Session filtering remains in the core router.
 */
public final class Paper26_2CaptureBridge implements CaptureBridge {

    private final PaperConnectionAccessor accessor;
    private final PacketRegistry registry;
    private final Paper26_2EntityStateCapture entityStateCapture;
    private final Paper26_2PlayerSceneIndex playerScenes;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final Set<Paper26_2SyntheticStateCollector> syntheticCollectors =
            ConcurrentHashMap.newKeySet();
    private final Object handlerStateLock = new Object();
    private final Paper26_2CaptureOrder captureOrder;
    private final PacketListener packetListener = new PacketListener() {
        @Override
        public void onPacketSend(PacketSendEvent event) {
            scheduleCapture(event);
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            capture(event);
        }
    };

    private volatile CaptureBridge.PacketSink sink;
    private volatile CaptureBridge.FailureHandler failureHandler;
    private volatile PacketListenerCommon installedListener;

    /** Creates a bridge and discovers the live Paper 26.2 registry. */
    public Paper26_2CaptureBridge(PaperConnectionAccessor accessor) {
        this(accessor, Paper26_2PacketRegistry.discover());
    }

    /**
     * Creates a bridge using an already verified Paper registry.
     *
     * @param accessor Paper connection boundary
     * @param registry registry published by the Paper 26.2 adapter
     */
    public Paper26_2CaptureBridge(PaperConnectionAccessor accessor, PacketRegistry registry) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.registry = verifyRegistry(registry);
        this.captureOrder = new Paper26_2CaptureOrder(
                System::nanoTime, this.accessor::currentServerTick);
        this.entityStateCapture = null;
        this.playerScenes = null;
    }

    /** Creates the production bridge with recipient-independent entity movement capture. */
    public Paper26_2CaptureBridge(
            JavaPlugin owner,
            PaperConnectionAccessor accessor,
            PacketRegistry registry,
            Paper26_2NativePacketCodec codec) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.registry = verifyRegistry(registry);
        this.captureOrder = new Paper26_2CaptureOrder(
                System::nanoTime, this.accessor::currentServerTick);
        this.playerScenes = new Paper26_2PlayerSceneIndex(owner);
        this.entityStateCapture = new Paper26_2EntityStateCapture(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(codec, "codec"),
                this::hasActiveRecording,
                this.accessor::currentServerTick,
                this::captureSyntheticEntityState,
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
            if (playerScenes != null) {
                playerScenes.install();
            }
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
        closePlayerScenes();
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
        captureOrder.publish(point -> {
            CaptureBridge.CapturePacket captured = new CaptureBridge.CapturePacket(
                    point.captureTimeNanos(),
                    point.serverTick(),
                    point.sequence(),
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
        });
    }

    private void captureSyntheticEntityState(
            Paper26_2SyntheticEntityState statePacket) {
        if (state.get() != State.INSTALLED) {
            return;
        }
        CaptureBridge.PacketSink currentSink = sink;
        if (currentSink == null || !currentSink.hasActiveSinks()) {
            return;
        }
        var packet = statePacket.packet();
        captureOrder.publish(point -> {
            currentSink.accept(new CaptureBridge.CapturePacket(
                    point.captureTimeNanos(),
                    point.serverTick(),
                    point.sequence(),
                    PacketPhase.PLAY,
                    packet.packetId(),
                    packet.payload(),
                    new CaptureContext(
                            packet.descriptor().disposition(),
                            Optional.of(statePacket.world()),
                            Optional.of(statePacket.position()),
                            Optional.empty(),
                            Optional.empty())));
        });
    }

    /**
     * Creates a collector whose accepted synthetic packets are routed through
     * this bridge. The caller owns and closes the returned collector together
     * with the recording session.
     */
    public Paper26_2SyntheticStateCollector syntheticCollector(
            RecordingScope scope,
            Function<CaptureBridge.CapturePacket, Paper26_2SyntheticStateCollector.ObservedStateKey>
                    observationDecoder,
            Function<Paper26_2SyntheticStateCollector.StateDelta,
                    Paper26_2SyntheticStateCollector.SyntheticStatePacket> deltaEncoder,
            dev.voldechse.replayframework.adapter.CheckpointSignalSource checkpointSignals) {
        if (state.get() != State.INSTALLED) {
            throw new IllegalStateException("Paper capture bridge is not installed");
        }
        AtomicReference<Paper26_2SyntheticStateCollector> collectorReference =
                new AtomicReference<>();
        Paper26_2SyntheticStateCollector collector = new Paper26_2SyntheticStateCollector(
                registry,
                scope,
                observationDecoder,
                deltaEncoder,
                checkpointSignals,
                this::captureSynthetic,
                () -> {
                    Paper26_2SyntheticStateCollector current = collectorReference.get();
                    if (current != null) {
                        syntheticCollectors.remove(current);
                    }
                });
        collectorReference.set(collector);
        syntheticCollectors.add(collector);
        return collector;
    }

    private void scheduleCapture(PacketSendEvent event) {
        if (state.get() != State.INSTALLED || event.isCancelled()) {
            return;
        }
        event.getPostTasks().add(() -> captureAfterPacketEvents(event));
    }

    /** Captures the buffer after every PacketEvents listener and wrapper rewrite completed. */
    private void captureAfterPacketEvents(PacketSendEvent event) {
        if (state.get() != State.INSTALLED || event.isCancelled()) {
            return;
        }
        try {
            PacketPhase phase = phaseOf(event);
            ObservedWirePacket wirePacket = copyWirePacket(event.getByteBuf());
            int packetId = wirePacket.packetId();
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

            byte[] payload = wirePacket.payload();
            if (entityStateCapture != null
                    && entityStateCapture.suppressesRecipientState(
                            descriptor.typeName(), payload, accessor.currentServerTick())) {
                return;
            }
            captureOrder.publish(point -> {
                CaptureBridge.CapturePacket packet = new CaptureBridge.CapturePacket(
                        point.captureTimeNanos(),
                        point.serverTick(),
                        point.sequence(),
                        phase,
                        packetId,
                        payload,
                        captureContext(
                                event,
                                descriptor,
                                phase,
                                packetId,
                                payload,
                                point.serverTick()));
                CaptureBridge.PacketSink currentSink = sink;
                if (currentSink != null) {
                    currentSink.accept(packet);
                }
                for (Paper26_2SyntheticStateCollector collector : syntheticCollectors) {
                    collector.observe(packet);
                }
            });
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void capture(PacketReceiveEvent event) {
        if (state.get() != State.INSTALLED || event.isCancelled()
                || entityStateCapture == null
                || !"PLAY".equals(event.getConnectionState().name())) {
            return;
        }
        try {
            entityStateCapture.captureClientPacket(event);
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
        Object rawChannel = event.getChannel();
        UUID playerId = event.getUser().getUUID();
        Optional<Key> world = playerScenes == null
                ? Optional.empty()
                : playerScenes.world(playerId);
        if (rawChannel instanceof Channel channel) {
            PaperConnectionAccessor.ConnectionHandle connection =
                    new PaperConnectionAccessor.ConnectionHandle(playerId, channel);
            if (phase == PacketPhase.PLAY) {
                Object decoded = accessor.decodeCapturedFrame(
                        connection,
                        new RawPacketFrame(0L, serverTick, 0, phase, packetId, payload),
                        registry);
                return accessor.captureContext(decoded, descriptor, world);
            }
            return new CaptureContext(
                    descriptor.disposition(),
                    world,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
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

    static ObservedWirePacket copyWirePacket(Object raw) {
        if (!(raw instanceof ByteBuf buffer)) {
            throw incompatible("PacketEvents did not expose a Netty byte buffer", null);
        }
        int index = buffer.readerIndex();
        int packetId = 0;
        int shift = 0;
        while (true) {
            if (index >= buffer.writerIndex() || shift >= 35) {
                throw incompatible("PacketEvents exposed an invalid packet VarInt", null);
            }
            int value = buffer.getUnsignedByte(index++);
            packetId |= (value & 0x7F) << shift;
            if ((value & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        if (packetId < 0) {
            throw incompatible("PacketEvents exposed a negative packet ID", null);
        }
        byte[] payload = new byte[buffer.writerIndex() - index];
        buffer.getBytes(index, payload);
        return new ObservedWirePacket(packetId, payload);
    }

    record ObservedWirePacket(int packetId, byte[] payload) {
        ObservedWirePacket {
            if (packetId < 0) {
                throw new IllegalArgumentException("packetId must not be negative");
            }
            Objects.requireNonNull(payload, "payload");
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
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

    private void closePlayerScenes() {
        if (playerScenes != null) {
            playerScenes.close();
        }
    }

    /** Allocates an exclusive checkpoint boundary in capture publication order. */
    public CheckpointEncoder.CapturePoint checkpointCapturePoint() {
        return captureOrder.barrier();
    }

    private void closeSyntheticCollectors() {
        List<Paper26_2SyntheticStateCollector> collectors =
                new ArrayList<>(syntheticCollectors);
        syntheticCollectors.clear();
        for (Paper26_2SyntheticStateCollector collector : collectors) {
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
        if (!(registry instanceof Paper26_2PacketRegistry)) {
            throw incompatible("Paper capture requires the Paper 26.2 packet registry", null);
        }
        String liveFingerprint = Paper26_2PacketRegistry.discover().fingerprint();
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
