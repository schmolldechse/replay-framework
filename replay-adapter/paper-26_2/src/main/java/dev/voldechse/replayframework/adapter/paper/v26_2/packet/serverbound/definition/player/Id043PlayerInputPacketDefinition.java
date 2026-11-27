package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:player_input}.
 *
 * Movement keys, sneaking and jumping. The results are entity metadata sent
 * through the tracker.
 */
final class Id043PlayerInputPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x2B;
    static final String IDENTIFIER = "minecraft:player_input";

    Id043PlayerInputPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
