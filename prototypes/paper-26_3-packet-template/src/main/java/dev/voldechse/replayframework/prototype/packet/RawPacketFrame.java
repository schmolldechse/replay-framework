package dev.voldechse.replayframework.prototype.packet;

import java.util.Objects;

/** Minimal prototype equivalent of the framework's PLAY clientbound frame. */
public record RawPacketFrame(int packetId, byte[] payload) {

    public RawPacketFrame {
        if (packetId < 0) {
            throw new IllegalArgumentException("packetId must not be negative");
        }
        payload = Objects.requireNonNull(payload, "payload").clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
