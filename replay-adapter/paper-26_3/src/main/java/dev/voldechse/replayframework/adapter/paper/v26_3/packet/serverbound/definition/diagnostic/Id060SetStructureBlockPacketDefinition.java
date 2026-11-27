package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:set_structure_block}.
 *
 * Configures a structure block. Its block entity data is sent as a
 * clientbound packet.
 */
final class Id060SetStructureBlockPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x3C;
    static final String IDENTIFIER = "minecraft:set_structure_block";

    Id060SetStructureBlockPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
