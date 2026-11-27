package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:player_command}.
 *
 * Sprinting, elytra flight, horse jumps, leaving a bed. The results are
 * entity metadata sent through the tracker.
 */
final class Id042PlayerCommandPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x2A;
    static final String IDENTIFIER = "minecraft:player_command";

    Id042PlayerCommandPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
