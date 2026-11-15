package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2CheckpointEncoder;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2NativePacketCodec;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Global Paper 26.2 capture installation shared by all active recording
 * sessions. Session filtering remains in the core router.
 */
public final class Paper26_2CaptureBridge implements CaptureBridge {

    private final PaperConnectionAccessor accessor;
    private final PacketRegistry registry;
    private final JavaPlugin owner;
    private final Paper26_2AuthoritativeStateIndex captureState;
    private final Paper26_2PlayerStateCapture playerStateCapture;
    private final PacketCopyDeduplicator packetCopyDeduplicator = new PacketCopyDeduplicator();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicBoolean failureReported = new AtomicBoolean();
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

        @Override
        public void onUserDisconnect(UserDisconnectEvent event) {
            UUID connectionId = event.getUser().getUUID();
            if (connectionId == null) {
                return;
            }
            captureState.removeConnection(connectionId);
            if (playerStateCapture != null) {
                playerStateCapture.remove(connectionId);
            }
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
        this.owner = null;
        this.captureState = new Paper26_2AuthoritativeStateIndex();
        this.playerStateCapture = null;
        this.captureOrder = new Paper26_2CaptureOrder(
                System::nanoTime, this.accessor::currentServerTick);
    }

    /** Creates the production bridge with definition-owned packet capture and player self-sampling. */
    public Paper26_2CaptureBridge(
            JavaPlugin owner,
            PaperConnectionAccessor accessor,
            PacketRegistry registry,
            Paper26_2NativePacketCodec codec) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.registry = verifyRegistry(registry);
        this.owner = Objects.requireNonNull(owner, "owner");
        this.captureState = new Paper26_2AuthoritativeStateIndex();
        this.captureOrder = new Paper26_2CaptureOrder(
                System::nanoTime, this.accessor::currentServerTick);
        this.playerStateCapture = new Paper26_2PlayerStateCapture(
                owner,
                Objects.requireNonNull(codec, "codec"),
                this::hasActiveRecording,
                this.accessor::currentServerTick,
                this::captureSyntheticEntityState,
                this::fail,
                captureState);
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
            seedOnlineConnections();
            installedListener = PacketEvents.getAPI().getEventManager().registerListener(
                    packetListener, PacketListenerPriority.MONITOR);
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
        closePlayerStateCapture();
        captureState.clear();
        packetCopyDeduplicator.clear();
    }

    @Override
    public boolean installed() {
        return state.get() == State.INSTALLED;
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
            if (!registry.captureAllowed(phase, PacketDescriptor.Direction.CLIENTBOUND, packetId)) {
                return;
            }

            byte[] payload = wirePacket.payload();
            UUID connectionId = event.getUser().getUUID();
            captureOrder.publish(point -> {
                CaptureContext context;
                if (phase == PacketPhase.PLAY) {
                    Object rawChannel = event.getChannel();
                    if (!(rawChannel instanceof Channel channel) || connectionId == null) {
                        throw incompatible("PLAY capture did not expose its Paper connection", null);
                    }
                    PacketDefinition<?> definition = Paper26_2PacketCatalog.catalog()
                            .find(packetId)
                            .orElseThrow(() -> incompatible(
                                    "PLAY packet has no definition for ID " + packetId, null));
                    PaperConnectionAccessor.ConnectionHandle connection =
                            new PaperConnectionAccessor.ConnectionHandle(connectionId, channel);
                    RawPacketFrame frame = new RawPacketFrame(
                            point.captureTimeNanos(), point.serverTick(), point.sequence(),
                            phase, packetId, payload);
                    Object decoded = accessor.decodeCapturedFrame(connection, frame, registry);
                    context = definition.captureContext(
                            decoded, descriptor, connectionId, captureState, point.serverTick());
                    if (definition.suppressesRecipientState(
                            decoded, captureState, point.serverTick())) {
                        return;
                    }
                    if (definition.deduplicateRecipientCopies()
                            && !packetCopyDeduplicator.first(
                                    point.serverTick(), packetId, context.world().orElse(null),
                                    connectionId, payload)) {
                        return;
                    }
                } else {
                    context = new CaptureContext(
                            descriptor.disposition(),
                            captureState.world(connectionId).map(Key::key),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());
                }
                CaptureBridge.CapturePacket packet = new CaptureBridge.CapturePacket(
                        point.captureTimeNanos(),
                        point.serverTick(),
                        point.sequence(),
                        phase,
                        packetId,
                        payload,
                        context);
                CaptureBridge.PacketSink currentSink = sink;
                if (currentSink != null) {
                    currentSink.accept(packet);
                }
            });
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void capture(PacketReceiveEvent event) {
        if (state.get() != State.INSTALLED || event.isCancelled()
                || playerStateCapture == null
                || !"PLAY".equals(event.getConnectionState().name())) {
            return;
        }
        try {
            playerStateCapture.capture(event);
        } catch (Throwable failure) {
            fail(failure);
        }
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
        closePlayerStateCapture();
        captureState.clear();
        packetCopyDeduplicator.clear();

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

    /** Allocates an exclusive checkpoint boundary in capture publication order. */
    public CheckpointEncoder.CapturePoint checkpointCapturePoint() {
        return captureOrder.barrier();
    }

    /** Seeds the relative-movement baseline from the authoritative checkpoint snapshot. */
    public void seedCheckpointEntityPosition(
            int entityId, String worldKey, double x, double y, double z) {
        captureState.seedEntityPosition(entityId, worldKey, x, y, z);
    }

    private boolean hasActiveRecording() {
        CaptureBridge.PacketSink currentSink = sink;
        return state.get() == State.INSTALLED
                && currentSink != null
                && currentSink.hasActiveSinks();
    }

    private void closePlayerStateCapture() {
        if (playerStateCapture != null) {
            playerStateCapture.close();
        }
    }

    /** Seeds context for connections already online when capture is installed. */
    private void seedOnlineConnections() {
        if (owner == null) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            throw incompatible("Paper capture must be installed on the server thread", null);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID connectionId = player.getUniqueId();
            Key world = player.getWorld().getKey();
            int entityId = player.getEntityId();
            var location = player.getLocation();
            captureState.world(connectionId, world.toString());
            captureState.connectionEntityId(connectionId, entityId);
            captureState.entityPosition(
                    connectionId, entityId, world.toString(),
                    location.getX(), location.getY(), location.getZ());
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

    static final class PacketCopyDeduplicator {
        private final ConcurrentMap<Long,
                        ConcurrentMap<PacketObservationSignature, RecipientCopySequence>>
                byTick =
                new ConcurrentHashMap<>();

        boolean first(long tick, int packetId, Key world, UUID connectionId, byte[] payload) {
            if (connectionId == null) {
                return true;
            }
            ConcurrentMap<PacketObservationSignature, RecipientCopySequence> signatures =
                    byTick.computeIfAbsent(tick, ignored -> new ConcurrentHashMap<>());
            RecipientCopySequence sequence = signatures.computeIfAbsent(
                    new PacketObservationSignature(packetId, world, payload),
                    ignored -> new RecipientCopySequence());
            boolean first = sequence.firstForRecipientOccurrence(connectionId);
            byTick.keySet().removeIf(observedTick -> observedTick < tick - 1L);
            return first;
        }

        void clear() {
            byTick.clear();
        }
    }

    private static final class RecipientCopySequence {
        private final ConcurrentMap<UUID, java.util.concurrent.atomic.AtomicInteger>
                nextOccurrenceByConnection = new ConcurrentHashMap<>();
        private final java.util.Set<Integer> publishedOccurrences = ConcurrentHashMap.newKeySet();

        private boolean firstForRecipientOccurrence(UUID connectionId) {
            int occurrence = nextOccurrenceByConnection
                    .computeIfAbsent(connectionId, ignored -> new java.util.concurrent.atomic.AtomicInteger())
                    .getAndIncrement();
            return publishedOccurrences.add(occurrence);
        }
    }

    private static final class PacketObservationSignature {
        private final int packetId;
        private final Key world;
        private final byte[] payload;
        private final int hashCode;

        private PacketObservationSignature(int packetId, Key world, byte[] payload) {
            this.packetId = packetId;
            this.world = world;
            this.payload = payload.clone();
            this.hashCode = 31 * (31 * packetId + Objects.hashCode(world))
                    + Arrays.hashCode(this.payload);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof PacketObservationSignature that
                    && packetId == that.packetId
                    && Objects.equals(world, that.world)
                    && Arrays.equals(payload, that.payload);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
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
