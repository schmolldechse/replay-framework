package dev.voldechse.replayframework.format;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable raw clientbound packet frame.
 *
 * @param elapsedNanos elapsed recording time in nanoseconds
 * @param serverTick server tick at capture time
 * @param sequence sequence within the server tick
 * @param phase protocol phase
 * @param packetId adapter-specific clientbound packet id
 * @param payload packet payload before transport compression and encryption
 */
public record RawPacketFrame(
        long elapsedNanos,
        long serverTick,
        int sequence,
        PacketPhase phase,
        int packetId,
        byte[] payload) {

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
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(elapsedNanos, serverTick, sequence, phase, packetId);
        return 31 * result + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "RawPacketFrame[elapsedNanos=" + elapsedNanos
                + ", serverTick=" + serverTick
                + ", sequence=" + sequence
                + ", phase=" + phase
                + ", packetId=" + packetId
                + ", payloadLength=" + payload.length
                + "]";
    }
}
