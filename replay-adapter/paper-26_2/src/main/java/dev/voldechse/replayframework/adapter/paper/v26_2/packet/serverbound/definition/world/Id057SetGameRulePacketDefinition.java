package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.world;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:set_game_rule}.
 *
 * Changes a game rule. A setting; its effects reach the recording as
 * clientbound packets.
 */
final class Id057SetGameRulePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x39;
    static final String IDENTIFIER = "minecraft:set_game_rule";

    Id057SetGameRulePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
