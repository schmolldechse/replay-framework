package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:set_command_minecart}.
 *
 * Configures a command minecart. Its metadata is sent through the tracker.
 */
final class Id055SetCommandMinecartPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x37;
    static final String IDENTIFIER = "minecraft:set_command_minecart";

    Id055SetCommandMinecartPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
