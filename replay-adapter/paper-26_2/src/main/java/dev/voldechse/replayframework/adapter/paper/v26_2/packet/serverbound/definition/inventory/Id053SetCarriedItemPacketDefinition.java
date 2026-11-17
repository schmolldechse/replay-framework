package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;

/**
 * Serverbound contract for {@code minecraft:set_carried_item}.
 *
 * Selects a hotbar slot. The sender's held item changes.
 */
final class Id053SetCarriedItemPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x35;
    static final String IDENTIFIER = "minecraft:set_carried_item";

    Id053SetCarriedItemPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public ServerboundReaction react(Object decodedPacket) {
        super.react(decodedPacket);
        return ServerboundReaction.PLAYER_STATE;
    }
}
