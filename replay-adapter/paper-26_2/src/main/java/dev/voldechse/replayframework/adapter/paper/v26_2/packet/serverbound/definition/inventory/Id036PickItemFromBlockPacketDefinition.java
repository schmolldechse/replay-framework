package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;

/**
 * Serverbound contract for {@code minecraft:pick_item_from_block}.
 *
 * Picks the block's item into the hand. The sender's held item may change.
 */
final class Id036PickItemFromBlockPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x24;
    static final String IDENTIFIER = "minecraft:pick_item_from_block";

    Id036PickItemFromBlockPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public ServerboundReaction react(Object decodedPacket) {
        super.react(decodedPacket);
        return ServerboundReaction.PLAYER_STATE;
    }
}
