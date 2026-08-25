package dev.voldechse.replayframework.core.capture;

import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable packet event shared by the recording sinks of one capture route.
 *
 * <p>The timestamp remains relative to the monotonic process clock. A recording
 * session rebases it to its own start time before creating a format frame.</p>
 */
public record CapturedPacket(
        UUID recipientId,
        long captureTimeNanos,
        long serverTick,
        int sequence,
        PacketPhase phase,
        int packetId,
        byte[] payload) {

    /** Validates packet metadata and takes ownership of a defensive payload copy. */
    public CapturedPacket {
        Objects.requireNonNull(recipientId, "recipientId");
        if (serverTick < 0L) {
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
    }

    /** Converts the adapter-owned event into the core-owned capture value once. */
    public static CapturedPacket from(CaptureBridge.CapturePacket packet) {
        Objects.requireNonNull(packet, "packet");
        return new CapturedPacket(
                packet.recipientId(),
                packet.captureTimeNanos(),
                packet.serverTick(),
                packet.sequence(),
                packet.phase(),
                packet.packetId(),
                packet.payload());
    }

    /** Returns a copy so a sink cannot mutate the packet shared with other sinks. */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CapturedPacket that)) {
            return false;
        }
        return captureTimeNanos == that.captureTimeNanos
                && serverTick == that.serverTick
                && sequence == that.sequence
                && packetId == that.packetId
                && recipientId.equals(that.recipientId)
                && phase == that.phase
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(recipientId, captureTimeNanos, serverTick, sequence, phase, packetId);
        return 31 * result + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "CapturedPacket[recipientId=" + recipientId
                + ", captureTimeNanos=" + captureTimeNanos
                + ", serverTick=" + serverTick
                + ", sequence=" + sequence
                + ", phase=" + phase
                + ", packetId=" + packetId
                + ", payloadLength=" + payload.length
                + "]";
    }
}
