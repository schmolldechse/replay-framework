package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:change_difficulty}.
 *
 * Changes the difficulty. The server announces the result with a
 * clientbound change_difficulty packet to every player.
 */
final class Id004ChangeDifficultyPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x04;
    static final String IDENTIFIER = "minecraft:change_difficulty";

    Id004ChangeDifficultyPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
