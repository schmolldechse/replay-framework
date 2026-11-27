package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:teleport_to_entity}.
 *
 * Spectator teleport to an entity. Whatever it changes about the sender's
 * own entity reaches the recording through the entity tracker of the eye
 * that follows the sender.
 */
final class Id064TeleportToEntityPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x40;
    static final String IDENTIFIER = "minecraft:teleport_to_entity";

    Id064TeleportToEntityPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
