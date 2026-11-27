package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.chat;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:chat_command}.
 *
 * Runs an unsigned command. Whatever the command changes reaches the
 * recording as clientbound packets.
 */
final class Id007ChatCommandPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x07;
    static final String IDENTIFIER = "minecraft:chat_command";

    Id007ChatCommandPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
