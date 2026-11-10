package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_entity_link}. */
final class SetEntityLinkPacketDefinition extends CatalogPacketDefinition {

    SetEntityLinkPacketDefinition(PacketClassification classification) {
        super("minecraft:set_entity_link", classification, Behavior.IDENTITY);
    }
}

