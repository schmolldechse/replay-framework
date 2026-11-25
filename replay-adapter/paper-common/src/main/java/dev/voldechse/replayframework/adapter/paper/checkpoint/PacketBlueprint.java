package dev.voldechse.replayframework.adapter.paper.checkpoint;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily;
import java.util.Objects;

/**
 * One detached native packet of a checkpoint, before the codec assigns its
 * wire ID.
 *
 * @param descriptorTypeName the type name of the packet's descriptor
 * @param family the checkpoint family the packet belongs to
 * @param worldKey the world the packet describes
 * @param targetKey what the packet describes within its world, for ordering
 * @param nativePacket the version's packet object
 */
public record PacketBlueprint(
        String descriptorTypeName,
        PacketCheckpointFamily family,
        String worldKey,
        String targetKey,
        Object nativePacket) {

    public PacketBlueprint {
        descriptorTypeName = StableText.require(descriptorTypeName, "descriptorTypeName");
        Objects.requireNonNull(family, "family");
        worldKey = StableText.require(worldKey, "worldKey");
        targetKey = StableText.require(targetKey, "targetKey");
        Objects.requireNonNull(nativePacket, "nativePacket");
    }
}
