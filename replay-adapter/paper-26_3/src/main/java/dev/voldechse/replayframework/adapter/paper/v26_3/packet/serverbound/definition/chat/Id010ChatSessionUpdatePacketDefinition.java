package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.chat;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:chat_session_update}.
 *
 * Publishes the sender's chat key. Other players receive it as a
 * clientbound player_info_update.
 */
final class Id010ChatSessionUpdatePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x0A;
    static final String IDENTIFIER = "minecraft:chat_session_update";

    Id010ChatSessionUpdatePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
