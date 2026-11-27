package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:ping_request}.
 *
 * Measures latency. Connection state only.
 */
final class Id038PingRequestPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x26;
    static final String IDENTIFIER = "minecraft:ping_request";

    Id038PingRequestPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
