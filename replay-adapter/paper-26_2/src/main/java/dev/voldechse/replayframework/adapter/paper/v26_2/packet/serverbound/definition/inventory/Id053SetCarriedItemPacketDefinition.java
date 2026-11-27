package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:set_carried_item}.
 *
 * Selects a hotbar slot. Whatever it changes about the sender's own entity
 * reaches the recording through the entity tracker of the eye that follows
 * the sender.
 */
final class Id053SetCarriedItemPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x35;
    static final String IDENTIFIER = "minecraft:set_carried_item";

    Id053SetCarriedItemPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
