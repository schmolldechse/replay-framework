package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:pong}.
 *
 * Answers a ping. Connection state only.
 */
final class Id045PongPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x2D;
    static final String IDENTIFIER = "minecraft:pong";

    Id045PongPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
