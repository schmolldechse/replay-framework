package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:move_player_pos_rot}.
 *
 * Moves and turns the sender. Whatever it changes about the sender's own
 * entity reaches the recording through the entity tracker of the eye that
 * follows the sender. Known gap: step, swim, splash and landing sounds are
 * played to everyone except the sender; they are not derived yet.
 */
final class Id031MovePlayerPosRotPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x1F;
    static final String IDENTIFIER = "minecraft:move_player_pos_rot";

    Id031MovePlayerPosRotPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
