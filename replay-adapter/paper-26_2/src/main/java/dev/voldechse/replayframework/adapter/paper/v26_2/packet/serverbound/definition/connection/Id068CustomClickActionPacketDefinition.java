package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:custom_click_action}.
 *
 * Clicks a custom dialog action. Whatever a plugin changes in response
 * reaches the recording as clientbound packets.
 */
final class Id068CustomClickActionPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x44;
    static final String IDENTIFIER = "minecraft:custom_click_action";

    Id068CustomClickActionPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
