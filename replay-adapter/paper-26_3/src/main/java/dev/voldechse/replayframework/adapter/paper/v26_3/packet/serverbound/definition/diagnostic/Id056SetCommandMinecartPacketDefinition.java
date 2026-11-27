package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:set_command_minecart}.
 *
 * Configures a command minecart. Its metadata is sent through the tracker.
 */
final class Id056SetCommandMinecartPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x38;
    static final String IDENTIFIER = "minecraft:set_command_minecart";

    Id056SetCommandMinecartPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
