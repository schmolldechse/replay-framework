package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:set_command_block}.
 *
 * Configures a command block. Its block entity data is sent as a
 * clientbound packet.
 */
final class Id055SetCommandBlockPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x37;
    static final String IDENTIFIER = "minecraft:set_command_block";

    Id055SetCommandBlockPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
