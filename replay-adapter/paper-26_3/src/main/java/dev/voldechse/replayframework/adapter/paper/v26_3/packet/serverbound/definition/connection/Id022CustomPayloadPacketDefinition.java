package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:custom_payload}.
 *
 * Plugin channel message. Whatever a plugin changes in response reaches the
 * recording as clientbound packets.
 */
final class Id022CustomPayloadPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x16;
    static final String IDENTIFIER = "minecraft:custom_payload";

    Id022CustomPayloadPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
