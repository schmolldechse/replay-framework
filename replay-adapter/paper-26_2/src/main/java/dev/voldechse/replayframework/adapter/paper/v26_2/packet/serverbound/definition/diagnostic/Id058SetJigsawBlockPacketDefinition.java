package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:set_jigsaw_block}.
 *
 * Configures a jigsaw block. Its block entity data is sent as a clientbound
 * packet.
 */
final class Id058SetJigsawBlockPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x3A;
    static final String IDENTIFIER = "minecraft:set_jigsaw_block";

    Id058SetJigsawBlockPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
