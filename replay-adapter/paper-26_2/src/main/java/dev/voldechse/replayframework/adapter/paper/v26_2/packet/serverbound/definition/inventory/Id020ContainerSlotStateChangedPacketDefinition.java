package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:container_slot_state_changed}.
 *
 * Toggles a crafter slot. The block change is sent as a clientbound block
 * update.
 */
final class Id020ContainerSlotStateChangedPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x14;
    static final String IDENTIFIER = "minecraft:container_slot_state_changed";

    Id020ContainerSlotStateChangedPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
