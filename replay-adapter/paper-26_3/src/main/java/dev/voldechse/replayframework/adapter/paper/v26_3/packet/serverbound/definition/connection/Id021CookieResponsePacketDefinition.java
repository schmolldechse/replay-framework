package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:cookie_response}.
 *
 * Answers a cookie request. Connection state only.
 */
final class Id021CookieResponsePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x15;
    static final String IDENTIFIER = "minecraft:cookie_response";

    Id021CookieResponsePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
