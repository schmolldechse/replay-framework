package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;

/**
 * Serverbound contract for {@code minecraft:set_creative_mode_slot}.
 *
 * Sets an inventory slot in creative mode. The sender's equipment may
 * change.
 */
final class Id056SetCreativeModeSlotPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x38;
    static final String IDENTIFIER = "minecraft:set_creative_mode_slot";

    Id056SetCreativeModeSlotPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public ServerboundReaction react(Object decodedPacket) {
        super.react(decodedPacket);
        return ServerboundReaction.PLAYER_STATE;
    }
}
