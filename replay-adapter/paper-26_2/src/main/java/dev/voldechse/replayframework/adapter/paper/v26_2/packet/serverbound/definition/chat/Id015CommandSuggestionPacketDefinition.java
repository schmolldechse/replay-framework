package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.chat;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:command_suggestion}.
 *
 * Requests tab completion. Changes nothing.
 */
final class Id015CommandSuggestionPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x0F;
    static final String IDENTIFIER = "minecraft:command_suggestion";

    Id015CommandSuggestionPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
