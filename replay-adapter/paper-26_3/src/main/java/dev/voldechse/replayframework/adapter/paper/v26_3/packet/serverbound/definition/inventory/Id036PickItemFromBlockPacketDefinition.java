package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:pick_item_from_block}.
 *
 * Picks the block's item into the hand. Whatever it changes about the
 * sender's own entity reaches the recording through the entity tracker of
 * the eye that follows the sender.
 */
final class Id036PickItemFromBlockPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x24;
    static final String IDENTIFIER = "minecraft:pick_item_from_block";

    Id036PickItemFromBlockPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
