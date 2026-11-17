package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;

/**
 * Serverbound contract for {@code minecraft:move_player_pos_rot}.
 *
 * Moves and turns the sender. Known gap: step, swim, splash and landing
 * sounds are played to everyone except the sender; they are not derived
 * yet.
 */
final class Id031MovePlayerPosRotPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x1F;
    static final String IDENTIFIER = "minecraft:move_player_pos_rot";

    Id031MovePlayerPosRotPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public ServerboundReaction react(Object decodedPacket) {
        super.react(decodedPacket);
        return ServerboundReaction.PLAYER_STATE;
    }
}
