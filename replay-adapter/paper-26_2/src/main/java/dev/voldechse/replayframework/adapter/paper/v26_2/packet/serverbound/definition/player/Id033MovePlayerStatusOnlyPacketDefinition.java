package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:move_player_status_only}.
 *
 * Reports ground and wall contact of the sender. Whatever it changes about
 * the sender's own entity reaches the recording through the entity tracker
 * of the eye that follows the sender.
 */
final class Id033MovePlayerStatusOnlyPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x21;
    static final String IDENTIFIER = "minecraft:move_player_status_only";

    Id033MovePlayerStatusOnlyPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
