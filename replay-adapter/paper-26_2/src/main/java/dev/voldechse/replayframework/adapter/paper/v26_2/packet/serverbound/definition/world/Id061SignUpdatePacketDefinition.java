package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.world;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:sign_update}.
 *
 * Sets the text of a sign. The sign's block entity data is sent to every
 * player.
 */
final class Id061SignUpdatePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x3D;
    static final String IDENTIFIER = "minecraft:sign_update";

    Id061SignUpdatePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
