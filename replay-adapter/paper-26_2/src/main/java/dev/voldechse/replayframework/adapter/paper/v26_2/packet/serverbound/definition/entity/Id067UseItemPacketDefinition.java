package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.entity;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;

/**
 * Serverbound contract for {@code minecraft:use_item}.
 *
 * Uses the held item in the air. The sender's equipment may change. Known
 * gap: bucket and bottle filling, goat horns, lance sounds and eating or
 * drinking are played to everyone except the sender; they are not derived
 * yet.
 */
final class Id067UseItemPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x43;
    static final String IDENTIFIER = "minecraft:use_item";

    Id067UseItemPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public ServerboundReaction react(Object decodedPacket) {
        super.react(decodedPacket);
        return ServerboundReaction.PLAYER_STATE;
    }
}
