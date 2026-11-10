package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:player_position}. */
final class PlayerPositionPacketDefinition extends CatalogPacketDefinition {

    PlayerPositionPacketDefinition(PacketClassification classification) {
        super("minecraft:player_position", classification);
    }
}

