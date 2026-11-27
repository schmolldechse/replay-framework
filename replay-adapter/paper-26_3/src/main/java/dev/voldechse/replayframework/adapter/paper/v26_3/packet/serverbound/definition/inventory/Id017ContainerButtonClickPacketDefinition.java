package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:container_button_click}.
 *
 * Clicks a menu button such as an enchantment option, loom pattern or
 * stonecutter recipe. Resulting sounds are sent to every player.
 */
final class Id017ContainerButtonClickPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x11;
    static final String IDENTIFIER = "minecraft:container_button_click";

    Id017ContainerButtonClickPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
