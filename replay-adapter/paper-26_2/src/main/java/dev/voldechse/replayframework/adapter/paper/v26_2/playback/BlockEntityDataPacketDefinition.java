package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:block_entity_data}. */
final class BlockEntityDataPacketDefinition extends CatalogPacketDefinition {

    BlockEntityDataPacketDefinition(PacketClassification classification) {
        super("minecraft:block_entity_data", classification);
    }
}

