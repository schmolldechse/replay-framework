package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.entity;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:swing}.
 *
 * Swings a hand. The server sends the animation to everyone except the
 * sender, so the eye that follows the sender receives it.
 */
final class Id063SwingPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x3F;
    static final String IDENTIFIER = "minecraft:swing";

    Id063SwingPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
