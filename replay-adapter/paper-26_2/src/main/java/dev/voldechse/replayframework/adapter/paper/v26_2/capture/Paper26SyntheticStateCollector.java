package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26CheckpointEncoder;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import net.kyori.adventure.key.Key;

/**
 * Session-local collector for state changes without a real outbound recipient.
 *
 * <p>The collector performs only bounded in-memory bookkeeping. It never
 * creates a sentinel recipient and never writes directly to the core queue.</p>
 */
public final class Paper26SyntheticStateCollector implements AutoCloseable {

    private static final PacketDescriptor.Direction CLIENTBOUND =
            PacketDescriptor.Direction.CLIENTBOUND;

    private final PacketRegistry registry;
    private final RecordingScope scope;
    private final Function<CaptureBridge.CapturePacket, ObservedStateKey> observationDecoder;
    private final Function<StateDelta, SyntheticStatePacket> deltaEncoder;
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<ObservedStateKey> observed = new HashSet<>();
    private final Set<ObservedStateKey> emitted = new HashSet<>();
    private long currentTick = -1L;
    private boolean closed;

    /** Creates one collector whose mutable index belongs to one recording session. */
    public Paper26SyntheticStateCollector(
            PacketRegistry registry,
            RecordingScope scope,
            Function<CaptureBridge.CapturePacket, ObservedStateKey> observationDecoder,
            Function<StateDelta, SyntheticStatePacket> deltaEncoder) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.observationDecoder = Objects.requireNonNull(observationDecoder, "observationDecoder");
        this.deltaEncoder = Objects.requireNonNull(deltaEncoder, "deltaEncoder");
    }

    /** Records one real outbound state observation for the current server tick. */
    public void observe(CaptureBridge.CapturePacket packet) {
        Objects.requireNonNull(packet, "packet");
        lock.lock();
        try {
            ensureOpen();
            PacketDescriptor descriptor = registry.find(
                            packet.phase(), CLIENTBOUND, packet.packetId())
                    .orElseThrow(() -> new IncompatibleAdapterException(
                            "observed packet is not registered: " + packet.packetId()));
            if (!registry.captureAllowed(
                    packet.phase(), CLIENTBOUND, packet.packetId())
                    || !descriptor.checkpointRelevant()) {
                return;
            }

            ObservedStateKey key = Objects.requireNonNull(
                    observationDecoder.apply(packet),
                    "observationDecoder returned null");
            if (key.serverTick() != packet.serverTick()) {
                throw new IllegalArgumentException(
                        "observed state key tick does not match packet tick");
            }
            if (Paper26CheckpointEncoder.familyFor(descriptor.typeName()) != key.family()) {
                throw new IncompatibleAdapterException(
                        "observed state key family does not match packet descriptor");
            }
            advanceTick(packet.serverTick());
            observed.add(key);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Emits at most one synthetic candidate for an unobserved state key.
     *
     * @param delta immutable state change
     * @return one candidate or an empty list when the scope excludes it or a
     *         real/synthetic equivalent already exists
     */
    public List<SyntheticStatePacket> collect(StateDelta delta) {
        Objects.requireNonNull(delta, "delta");
        lock.lock();
        try {
            ensureOpen();
            PacketDescriptor descriptor = registry.find(
                            PacketPhase.PLAY, CLIENTBOUND, delta.packetId())
                    .orElseThrow(() -> new IncompatibleAdapterException(
                            "synthetic state packet is not registered: " + delta.packetId()));
            if (!registry.replayAllowed(PacketPhase.PLAY, CLIENTBOUND, delta.packetId())
                    || !descriptor.checkpointRelevant()) {
                throw new IncompatibleAdapterException(
                        "synthetic state packet is not replayable: " + descriptor.typeName());
            }
            if (Paper26CheckpointEncoder.familyFor(descriptor.typeName()) != delta.family()) {
                throw new IncompatibleAdapterException(
                        "synthetic state family does not match packet descriptor");
            }
            advanceTick(delta.serverTick());
            if (!inScope(delta)) {
                return List.of();
            }

            ObservedStateKey key = delta.key();
            if (observed.contains(key) || emitted.contains(key)) {
                return List.of();
            }

            SyntheticStatePacket packet = Objects.requireNonNull(
                    deltaEncoder.apply(delta),
                    "deltaEncoder returned null");
            if (packet.captureTimeNanos() != delta.captureTimeNanos()
                    || packet.serverTick() != delta.serverTick()
                    || packet.packetId() != delta.packetId()
                    || packet.family() != delta.family()
                    || !packet.worldKey().equals(delta.worldKey())
                    || !packet.targetKey().equals(delta.targetKey())) {
                throw new IllegalArgumentException(
                        "deltaEncoder changed synthetic state identity");
            }
            emitted.add(key);
            return List.of(packet);
        } finally {
            lock.unlock();
        }
    }

    /** Closes this session-local collector and clears all retained keys. */
    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            observed.clear();
            emitted.clear();
        } finally {
            lock.unlock();
        }
    }

    private boolean inScope(StateDelta delta) {
        Key world;
        try {
            world = Key.key(delta.worldKey());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("state delta contains an invalid world key", exception);
        }

        if (!scope.worlds().isEmpty() && !scope.worlds().contains(world)) {
            return false;
        }
        if (scope.regions().isEmpty()) {
            return true;
        }

        for (CuboidRegion region : scope.regions()) {
            if (!region.world().equals(world)) {
                continue;
            }
            BlockPosition position = delta.position();
            if (position == null || contains(region, position)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(CuboidRegion region, BlockPosition position) {
        return position.x() >= region.min().x()
                && position.x() <= region.max().x()
                && position.y() >= region.min().y()
                && position.y() <= region.max().y()
                && position.z() >= region.min().z()
                && position.z() <= region.max().z();
    }

    private void advanceTick(long tick) {
        if (tick < 0L) {
            throw new IllegalArgumentException("serverTick must not be negative");
        }
        if (currentTick > tick) {
            throw new IllegalArgumentException("serverTick moved backwards");
        }
        if (currentTick != tick) {
            currentTick = tick;
            observed.clear();
            emitted.clear();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("synthetic state collector is closed");
        }
    }

    /** Identity of an observed or synthetic state change within one tick. */
    public record ObservedStateKey(
            long serverTick,
            Paper26CheckpointEncoder.CheckpointPacketFamily family,
            String worldKey,
            String targetKey) {
        public ObservedStateKey {
            if (serverTick < 0L) {
                throw new IllegalArgumentException("serverTick must not be negative");
            }
            Objects.requireNonNull(family, "family");
            worldKey = stableText(worldKey, "worldKey");
            targetKey = stableText(targetKey, "targetKey");
        }
    }

    /** Immutable adapter-local source state delta. */
    public record StateDelta(
            long captureTimeNanos,
            long serverTick,
            Paper26CheckpointEncoder.CheckpointPacketFamily family,
            String worldKey,
            String targetKey,
            BlockPosition position,
            int packetId,
            byte[] payload) {
        public StateDelta {
            if (captureTimeNanos < 0L) {
                throw new IllegalArgumentException("captureTimeNanos must not be negative");
            }
            if (serverTick < 0L) {
                throw new IllegalArgumentException("serverTick must not be negative");
            }
            Objects.requireNonNull(family, "family");
            worldKey = stableText(worldKey, "worldKey");
            targetKey = stableText(targetKey, "targetKey");
            if (packetId < 0) {
                throw new IllegalArgumentException("packetId must not be negative");
            }
            Objects.requireNonNull(payload, "payload");
            payload = payload.clone();
        }

        ObservedStateKey key() {
            return new ObservedStateKey(serverTick, family, worldKey, targetKey);
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    /** Candidate emitted without a real viewer identity or final recording sequence. */
    public record SyntheticStatePacket(
            long captureTimeNanos,
            long serverTick,
            PacketPhase phase,
            int packetId,
            Paper26CheckpointEncoder.CheckpointPacketFamily family,
            String worldKey,
            String targetKey,
            byte[] payload) {
        public SyntheticStatePacket {
            if (captureTimeNanos < 0L) {
                throw new IllegalArgumentException("captureTimeNanos must not be negative");
            }
            if (serverTick < 0L) {
                throw new IllegalArgumentException("serverTick must not be negative");
            }
            Objects.requireNonNull(phase, "phase");
            if (phase != PacketPhase.PLAY) {
                throw new IllegalArgumentException("synthetic state packets must use PLAY phase");
            }
            if (packetId < 0) {
                throw new IllegalArgumentException("packetId must not be negative");
            }
            Objects.requireNonNull(family, "family");
            worldKey = stableText(worldKey, "worldKey");
            targetKey = stableText(targetKey, "targetKey");
            Objects.requireNonNull(payload, "payload");
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    private static String stableText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                throw new IllegalArgumentException(field + " must not contain whitespace or control characters");
            }
        }
        return value;
    }
}
