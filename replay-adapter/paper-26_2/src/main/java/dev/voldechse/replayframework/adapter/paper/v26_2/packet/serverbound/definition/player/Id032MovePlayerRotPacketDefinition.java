package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;

/**
 * Serverbound contract for {@code minecraft:move_player_rot}.
 *
 * Turns the sender.
 */
final class Id032MovePlayerRotPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x20;
    static final String IDENTIFIER = "minecraft:move_player_rot";

    Id032MovePlayerRotPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public ServerboundReaction react(Object decodedPacket) {
        super.react(decodedPacket);
        return ServerboundReaction.PLAYER_STATE;
    }
}
