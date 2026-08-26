package dev.voldechse.replayframework.adapter.codec;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.Objects;

/**
 * Adapter-owned codec session for translating between a native packet object
 * and the version-neutral replay frame representation.
 *
 * <p>Each adapter creates this codec after binding a concrete viewer
 * connection. The core never receives a native packet or transport buffer.</p>
 *
 * @param <T> adapter-native packet representation
 */
public interface PacketCodec<T> {

    /** Encodes one native clientbound packet into a replay transport packet. */
    EncodedPacket encode(T packet);

    /** Decodes one replay frame that the registry already permits for playback. */
    T decode(RawPacketFrame frame);

    /** Immutable packet body without compression, encryption or packet framing. */
    record EncodedPacket(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId,
            byte[] payload) {

        public EncodedPacket {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(direction, "direction");
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
}
