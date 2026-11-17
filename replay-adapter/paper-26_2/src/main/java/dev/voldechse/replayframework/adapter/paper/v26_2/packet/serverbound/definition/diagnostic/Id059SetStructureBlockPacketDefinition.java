package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:set_structure_block}.
 *
 * Configures a structure block. Its block entity data is sent as a
 * clientbound packet.
 */
final class Id059SetStructureBlockPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x3B;
    static final String IDENTIFIER = "minecraft:set_structure_block";

    Id059SetStructureBlockPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
