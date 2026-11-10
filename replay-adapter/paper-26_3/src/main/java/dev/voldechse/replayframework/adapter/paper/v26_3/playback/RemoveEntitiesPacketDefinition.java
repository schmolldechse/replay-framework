package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:remove_entities}. */
final class RemoveEntitiesPacketDefinition extends CatalogPacketDefinition {

    RemoveEntitiesPacketDefinition(PacketClassification classification) {
        super("minecraft:remove_entities", classification, Behavior.IDENTITY);
    }
}

