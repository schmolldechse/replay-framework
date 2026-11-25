package dev.voldechse.replayframework.adapter.paper.checkpoint;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import java.util.Objects;

/** The version's encoder for the native packets of a checkpoint. */
@FunctionalInterface
public interface CheckpointCodec {

    /**
     * Encodes a scene packet.
     *
     * @param blueprint the packet and its keys
     * @return the encoded packet, keeping the blueprint's family and keys
     */
    EncodedPacket encode(PacketBlueprint blueprint);

    /**
     * Encodes a packet of a subject's or a block's state, which has no
     * checkpoint family.
     *
     * @param nativePacket the version's packet object
     * @return its wire ID and body
     * @throws IncompatibleAdapterException when this codec cannot encode state
     */
    default StatePacket encodeStatePacket(Object nativePacket) {
        throw new IncompatibleAdapterException("this codec cannot encode subject state");
    }

    /**
     * An encoded state packet.
     *
     * @param packetId the wire ID
     * @param payload the packet body without its ID
     */
    record StatePacket(int packetId, byte[] payload) {
        public StatePacket {
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
}
