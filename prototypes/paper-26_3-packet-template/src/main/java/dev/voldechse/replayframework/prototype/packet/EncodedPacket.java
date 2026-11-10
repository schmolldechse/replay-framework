package dev.voldechse.replayframework.prototype.packet;

import java.util.Objects;

/** Packet ID and uncompressed body, without transport framing. */
public record EncodedPacket(int packetId, byte[] payload) {

    public EncodedPacket {
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
