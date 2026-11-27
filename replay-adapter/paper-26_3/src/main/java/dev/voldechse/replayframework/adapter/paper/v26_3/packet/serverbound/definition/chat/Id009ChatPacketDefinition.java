package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.chat;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:chat}.
 *
 * Sends a chat message. The server sends the message to every recipient,
 * including the sender.
 */
final class Id009ChatPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x09;
    static final String IDENTIFIER = "minecraft:chat";

    Id009ChatPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
