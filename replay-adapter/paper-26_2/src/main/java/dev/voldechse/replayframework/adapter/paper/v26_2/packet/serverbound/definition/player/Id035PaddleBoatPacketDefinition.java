package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:paddle_boat}.
 *
 * Reports paddle state. Paddle sounds are played to every player.
 */
final class Id035PaddleBoatPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x23;
    static final String IDENTIFIER = "minecraft:paddle_boat";

    Id035PaddleBoatPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
