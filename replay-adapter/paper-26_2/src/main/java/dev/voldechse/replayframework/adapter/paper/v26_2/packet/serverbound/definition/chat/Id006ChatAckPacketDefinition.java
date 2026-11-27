package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.chat;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:chat_ack}.
 *
 * Acknowledges received chat messages for the signature chain. Connection
 * state only.
 */
final class Id006ChatAckPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x06;
    static final String IDENTIFIER = "minecraft:chat_ack";

    Id006ChatAckPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
