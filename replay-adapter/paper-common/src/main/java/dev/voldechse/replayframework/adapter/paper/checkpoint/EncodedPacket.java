package dev.voldechse.replayframework.adapter.paper.checkpoint;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.Objects;

/**
 * A checkpoint packet as the {@link CheckpointCodec} encoded it.
 *
 * @param descriptorTypeName the type name of the packet's descriptor
 * @param phase the protocol phase
 * @param direction the packet direction
 * @param packetId the wire ID
 * @param family the checkpoint family, as given by the blueprint
 * @param worldKey the world, as given by the blueprint
 * @param targetKey the target, as given by the blueprint
 * @param payload the packet body without its ID
 */
public record EncodedPacket(
        String descriptorTypeName,
        PacketPhase phase,
        PacketDescriptor.Direction direction,
        int packetId,
        PacketCheckpointFamily family,
        String worldKey,
        String targetKey,
        byte[] payload) {

    public EncodedPacket {
        descriptorTypeName = StableText.require(descriptorTypeName, "descriptorTypeName");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(direction, "direction");
        if (packetId < 0) {
            throw new IllegalArgumentException("packetId must not be negative");
        }
        Objects.requireNonNull(family, "family");
        worldKey = StableText.require(worldKey, "worldKey");
        targetKey = StableText.require(targetKey, "targetKey");
        payload = Objects.requireNonNull(payload, "payload").clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
