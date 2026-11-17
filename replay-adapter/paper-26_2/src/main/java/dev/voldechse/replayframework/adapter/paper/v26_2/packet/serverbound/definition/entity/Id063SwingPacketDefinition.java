package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.entity;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;

/**
 * Serverbound contract for {@code minecraft:swing}.
 *
 * Swings a hand. The server sends the animation to everyone except the
 * sender, so the swing is emitted with the sender's sampled state.
 */
final class Id063SwingPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x3F;
    static final String IDENTIFIER = "minecraft:swing";

    Id063SwingPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public ServerboundReaction react(Object decodedPacket) {
        InteractionHand hand = ServerboundSwingPacket.class.cast(decodedPacket).getHand();
        return ServerboundReaction.swing(hand == InteractionHand.OFF_HAND
                ? ServerboundReaction.SwingHand.OFF_HAND
                : ServerboundReaction.SwingHand.MAIN_HAND);
    }
}
