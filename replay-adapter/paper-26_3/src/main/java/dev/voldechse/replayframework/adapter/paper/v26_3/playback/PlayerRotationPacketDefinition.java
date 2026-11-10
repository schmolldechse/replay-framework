package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:player_rotation}. */
final class PlayerRotationPacketDefinition extends CatalogPacketDefinition {

    PlayerRotationPacketDefinition(PacketClassification classification) {
        super("minecraft:player_rotation", classification);
    }
}

