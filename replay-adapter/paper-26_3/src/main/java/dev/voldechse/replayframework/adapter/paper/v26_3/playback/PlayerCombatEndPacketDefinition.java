package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:player_combat_end}. */
final class PlayerCombatEndPacketDefinition extends CatalogPacketDefinition {

    PlayerCombatEndPacketDefinition(PacketClassification classification) {
        super("minecraft:player_combat_end", classification);
    }
}

