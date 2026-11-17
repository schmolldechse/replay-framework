package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:set_creative_mode_slot}.
 *
 * Sets an inventory slot in creative mode. Whatever it changes about the
 * sender's own entity reaches the recording through the entity tracker of
 * the eye that follows the sender.
 */
final class Id056SetCreativeModeSlotPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x38;
    static final String IDENTIFIER = "minecraft:set_creative_mode_slot";

    Id056SetCreativeModeSlotPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
