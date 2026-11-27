package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:pick_item_from_entity}.
 *
 * Picks the entity's item into the hand. Whatever it changes about the
 * sender's own entity reaches the recording through the entity tracker of
 * the eye that follows the sender.
 */
final class Id037PickItemFromEntityPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x25;
    static final String IDENTIFIER = "minecraft:pick_item_from_entity";

    Id037PickItemFromEntityPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
