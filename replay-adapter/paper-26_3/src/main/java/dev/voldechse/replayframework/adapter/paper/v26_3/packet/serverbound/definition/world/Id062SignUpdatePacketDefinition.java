package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.world;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:sign_update}.
 *
 * Sets the text of a sign. The sign's block entity data is sent to every
 * player.
 */
final class Id062SignUpdatePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x3E;
    static final String IDENTIFIER = "minecraft:sign_update";

    Id062SignUpdatePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
