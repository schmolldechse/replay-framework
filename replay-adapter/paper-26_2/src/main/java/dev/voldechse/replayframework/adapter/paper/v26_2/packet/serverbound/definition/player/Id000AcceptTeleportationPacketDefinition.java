package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:accept_teleportation}.
 *
 * Confirms a server-side teleport. Whatever it changes about the sender's
 * own entity reaches the recording through the entity tracker of the eye
 * that follows the sender.
 */
final class Id000AcceptTeleportationPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x00;
    static final String IDENTIFIER = "minecraft:accept_teleportation";

    Id000AcceptTeleportationPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
