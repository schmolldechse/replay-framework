package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:change_game_mode}.
 *
 * Switches the sender's game mode. Every player receives the result as a
 * clientbound player_info_update.
 */
final class Id005ChangeGameModePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x05;
    static final String IDENTIFIER = "minecraft:change_game_mode";

    Id005ChangeGameModePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
