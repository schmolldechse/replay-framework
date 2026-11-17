package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:keep_alive}.
 *
 * Answers a keep-alive. Connection state only.
 */
final class Id028KeepAlivePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x1C;
    static final String IDENTIFIER = "minecraft:keep_alive";

    Id028KeepAlivePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
