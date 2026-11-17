package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;

/**
 * Serverbound contract for {@code minecraft:teleport_to_entity}.
 *
 * Spectator teleport to an entity. The sender's position changes.
 */
final class Id064TeleportToEntityPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x40;
    static final String IDENTIFIER = "minecraft:teleport_to_entity";

    Id064TeleportToEntityPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public ServerboundReaction react(Object decodedPacket) {
        super.react(decodedPacket);
        return ServerboundReaction.PLAYER_STATE;
    }
}
