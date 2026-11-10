package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:entity_event}. */
final class EntityEventPacketDefinition extends CatalogPacketDefinition {

    EntityEventPacketDefinition(PacketClassification classification) {
        super("minecraft:entity_event", classification, Behavior.IDENTITY);
    }
}

