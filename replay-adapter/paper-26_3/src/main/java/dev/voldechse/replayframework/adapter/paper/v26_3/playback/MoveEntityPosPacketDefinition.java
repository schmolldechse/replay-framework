package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:move_entity_pos}. */
final class MoveEntityPosPacketDefinition extends CatalogPacketDefinition {

    MoveEntityPosPacketDefinition(PacketClassification classification) {
        super("minecraft:move_entity_pos", classification, Behavior.IDENTITY);
    }
}

