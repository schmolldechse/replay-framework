package dev.voldechse.replayframework.format;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable raw clientbound packet frame.
 *
 * @param elapsedNanos elapsed recording time in nanoseconds
 * @param serverTick server tick at capture time
 * @param sequence sequence within the server tick
 * @param phase protocol phase
 * @param packetId adapter-specific clientbound packet id
 * @param payload packet payload before transport compression and encryption
 * @param world namespaced key of the world the packet belongs to; empty for
 *              packets that belong to no world, such as chat or the tab list.
 *              A recording can span several worlds, and a client can only
 *              apply the packets of the world it is in. The world is not
 *              stored per frame: it is the world of the frame's track.
 */
public record RawPacketFrame(
        long elapsedNanos,
        long serverTick,
        int sequence,
        PacketPhase phase,
        int packetId,
        byte[] payload,
        Optional<String> world) {

    /** Longest world key in UTF-8 bytes. */
    public static final int MAX_WORLD_LENGTH = 256;

    /** Creates a frame that belongs to no world. */
    public RawPacketFrame(
            long elapsedNanos,
            long serverTick,
            int sequence,
            PacketPhase phase,
            int packetId,
            byte[] payload) {
        this(elapsedNanos, serverTick, sequence, phase, packetId, payload, Optional.empty());
    }

    public RawPacketFrame {
        if (elapsedNanos < 0) {
            throw new IllegalArgumentException("elapsedNanos must not be negative");
        }
        if (serverTick < 0) {
            throw new IllegalArgumentException("serverTick must not be negative");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        Objects.requireNonNull(phase, "phase");
        if (packetId < 0) {
            throw new IllegalArgumentException("packetId must not be negative");
        }
        Objects.requireNonNull(payload, "payload");
        payload = payload.clone();
        Objects.requireNonNull(world, "world");
        world.ifPresent(RawPacketFrame::requireWorldKey);
    }

    /**
     * Validates a world key.
     *
     * @param key world key
     * @return the key
     * @throws IllegalArgumentException when it is blank or too long
     */
    static String requireWorldKey(String key) {
        Objects.requireNonNull(key, "world");
        if (key.isBlank()) {
            throw new IllegalArgumentException("world must not be blank");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_WORLD_LENGTH) {
            throw new IllegalArgumentException("world key is too long");
        }
        return key;
    }

    /**
     * Returns a defensive copy of the raw payload.
     *
     * @return copied payload bytes
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /** Whether a client in the given world applies this frame. */
    public boolean appliesTo(String viewedWorld) {
        Objects.requireNonNull(viewedWorld, "viewedWorld");
        return world.isEmpty() || world.get().equals(viewedWorld);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RawPacketFrame that)) {
            return false;
        }
        return elapsedNanos == that.elapsedNanos
                && serverTick == that.serverTick
                && sequence == that.sequence
                && phase == that.phase
                && packetId == that.packetId
                && world.equals(that.world)
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(elapsedNanos, serverTick, sequence, phase, packetId, world);
        return 31 * result + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "RawPacketFrame[elapsedNanos=" + elapsedNanos
                + ", serverTick=" + serverTick
                + ", sequence=" + sequence
                + ", phase=" + phase
                + ", packetId=" + packetId
                + ", world=" + world.orElse("-")
                + ", payloadLength=" + payload.length
                + "]";
    }
}
