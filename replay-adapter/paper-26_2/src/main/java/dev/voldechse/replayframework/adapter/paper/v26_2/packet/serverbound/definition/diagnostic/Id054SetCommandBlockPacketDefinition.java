package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:set_command_block}.
 *
 * Configures a command block. Its block entity data is sent as a
 * clientbound packet.
 */
final class Id054SetCommandBlockPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x36;
    static final String IDENTIFIER = "minecraft:set_command_block";

    Id054SetCommandBlockPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
