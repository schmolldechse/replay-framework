package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:player_abilities}. */
final class PlayerAbilitiesPacketDefinition extends CatalogPacketDefinition {

    PlayerAbilitiesPacketDefinition(PacketClassification classification) {
        super("minecraft:player_abilities", classification);
    }
}

