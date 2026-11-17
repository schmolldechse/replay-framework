package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;

/**
 * Serverbound contract for {@code minecraft:spectator_action}.
 *
 * Spectator teleport. The sender's position changes.
 */
final class Id062SpectatorActionPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x3E;
    static final String IDENTIFIER = "minecraft:spectator_action";

    Id062SpectatorActionPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public ServerboundReaction react(Object decodedPacket) {
        super.react(decodedPacket);
        return ServerboundReaction.PLAYER_STATE;
    }
}
