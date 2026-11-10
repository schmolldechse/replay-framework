package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:player_combat_kill}. */
final class PlayerCombatKillPacketDefinition extends CatalogPacketDefinition {

    PlayerCombatKillPacketDefinition(PacketClassification classification) {
        super("minecraft:player_combat_kill", classification);
    }
}

