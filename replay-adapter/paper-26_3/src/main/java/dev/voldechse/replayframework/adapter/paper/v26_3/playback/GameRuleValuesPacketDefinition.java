package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:game_rule_values}. */
final class GameRuleValuesPacketDefinition extends CatalogPacketDefinition {

    GameRuleValuesPacketDefinition(PacketClassification classification) {
        super("minecraft:game_rule_values", classification);
    }
}

