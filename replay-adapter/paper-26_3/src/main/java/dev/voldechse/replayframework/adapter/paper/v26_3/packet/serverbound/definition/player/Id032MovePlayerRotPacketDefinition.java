package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:move_player_rot}.
 *
 * Turns the sender. Whatever it changes about the sender's own entity
 * reaches the recording through the entity tracker of the eye that follows
 * the sender.
 */
final class Id032MovePlayerRotPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x20;
    static final String IDENTIFIER = "minecraft:move_player_rot";

    Id032MovePlayerRotPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
