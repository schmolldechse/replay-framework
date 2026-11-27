package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:container_click}.
 *
 * Moves items in an open container. Whatever it changes about the sender's
 * own entity reaches the recording through the entity tracker of the eye
 * that follows the sender.
 */
final class Id018ContainerClickPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x12;
    static final String IDENTIFIER = "minecraft:container_click";

    Id018ContainerClickPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
