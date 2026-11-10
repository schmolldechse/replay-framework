package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:move_entity_pos_rot}. */
final class MoveEntityPosRotPacketDefinition extends CatalogPacketDefinition {

    MoveEntityPosRotPacketDefinition(PacketClassification classification) {
        super("minecraft:move_entity_pos_rot", classification, Behavior.IDENTITY);
    }
}

