package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:move_entity_rot}. */
final class MoveEntityRotPacketDefinition extends CatalogPacketDefinition {

    MoveEntityRotPacketDefinition(PacketClassification classification) {
        super("minecraft:move_entity_rot", classification, Behavior.IDENTITY);
    }
}

