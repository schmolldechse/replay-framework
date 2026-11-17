package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:bundle_item_selected}.
 *
 * Selects a slot inside a bundle. Inventory state of the sender only.
 */
final class Id003BundleItemSelectedPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x03;
    static final String IDENTIFIER = "minecraft:bundle_item_selected";

    Id003BundleItemSelectedPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
