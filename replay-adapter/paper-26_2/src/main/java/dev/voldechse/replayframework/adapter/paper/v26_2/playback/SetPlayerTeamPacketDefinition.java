package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_player_team}. */
final class SetPlayerTeamPacketDefinition extends CatalogPacketDefinition {

    SetPlayerTeamPacketDefinition(PacketClassification classification) {
        super("minecraft:set_player_team", classification);
    }
}

