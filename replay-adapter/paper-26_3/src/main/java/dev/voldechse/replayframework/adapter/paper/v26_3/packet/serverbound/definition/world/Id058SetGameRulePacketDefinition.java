package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.world;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:set_game_rule}.
 *
 * Changes a game rule. A setting; its effects reach the recording as
 * clientbound packets.
 */
final class Id058SetGameRulePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x3A;
    static final String IDENTIFIER = "minecraft:set_game_rule";

    Id058SetGameRulePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
