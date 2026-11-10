package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:entity_position_sync}. */
final class EntityPositionSyncPacketDefinition extends CatalogPacketDefinition {

    EntityPositionSyncPacketDefinition(PacketClassification classification) {
        super("minecraft:entity_position_sync", classification, Behavior.IDENTITY);
    }
}

