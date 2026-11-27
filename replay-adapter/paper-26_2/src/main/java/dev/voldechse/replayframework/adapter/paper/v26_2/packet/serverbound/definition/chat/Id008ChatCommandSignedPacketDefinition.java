package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.chat;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:chat_command_signed}.
 *
 * Runs a signed command. Whatever the command changes reaches the recording
 * as clientbound packets.
 */
final class Id008ChatCommandSignedPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x08;
    static final String IDENTIFIER = "minecraft:chat_command_signed";

    Id008ChatCommandSignedPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
