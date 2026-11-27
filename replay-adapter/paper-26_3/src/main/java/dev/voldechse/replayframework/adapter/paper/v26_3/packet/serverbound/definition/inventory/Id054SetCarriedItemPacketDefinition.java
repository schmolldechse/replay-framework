package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:set_carried_item}.
 *
 * Selects a hotbar slot. Whatever it changes about the sender's own entity
 * reaches the recording through the entity tracker of the eye that follows
 * the sender.
 */
final class Id054SetCarriedItemPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x36;
    static final String IDENTIFIER = "minecraft:set_carried_item";

    Id054SetCarriedItemPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
