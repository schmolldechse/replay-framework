package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:set_test_block}.
 *
 * Configures a test block. Its block entity data is sent as a clientbound
 * packet.
 */
final class Id060SetTestBlockPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x3C;
    static final String IDENTIFIER = "minecraft:set_test_block";

    Id060SetTestBlockPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
