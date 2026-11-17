package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:spectator_action}.
 *
 * Spectator teleport. Whatever it changes about the sender's own entity
 * reaches the recording through the entity tracker of the eye that follows
 * the sender.
 */
final class Id062SpectatorActionPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x3E;
    static final String IDENTIFIER = "minecraft:spectator_action";

    Id062SpectatorActionPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
