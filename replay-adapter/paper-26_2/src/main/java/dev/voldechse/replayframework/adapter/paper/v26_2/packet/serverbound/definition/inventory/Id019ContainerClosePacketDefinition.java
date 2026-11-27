package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:container_close}.
 *
 * Closes a container. Chest, barrel and shulker box sounds and lid
 * animations are sent to every player.
 */
final class Id019ContainerClosePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x13;
    static final String IDENTIFIER = "minecraft:container_close";

    Id019ContainerClosePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
