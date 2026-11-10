package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_entity_data}. */
final class SetEntityDataPacketDefinition extends CatalogPacketDefinition {

    SetEntityDataPacketDefinition(PacketClassification classification) {
        super("minecraft:set_entity_data", classification, Behavior.IDENTITY);
    }
}

