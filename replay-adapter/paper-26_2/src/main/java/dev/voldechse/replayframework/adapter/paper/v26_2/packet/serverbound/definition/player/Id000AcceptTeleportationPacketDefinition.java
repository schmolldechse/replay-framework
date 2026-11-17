package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;

/**
 * Serverbound contract for {@code minecraft:accept_teleportation}.
 *
 * Confirms a server-side teleport. The confirmed position is the sender's
 * own entity position, which the player-state sampler reads.
 */
final class Id000AcceptTeleportationPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x00;
    static final String IDENTIFIER = "minecraft:accept_teleportation";

    Id000AcceptTeleportationPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public ServerboundReaction react(Object decodedPacket) {
        super.react(decodedPacket);
        return ServerboundReaction.PLAYER_STATE;
    }
}
