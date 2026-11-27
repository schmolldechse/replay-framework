package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:block_entity_tag_query}.
 *
 * Debug query for block-entity data. Changes nothing.
 */
final class Id002BlockEntityTagQueryPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x02;
    static final String IDENTIFIER = "minecraft:block_entity_tag_query";

    Id002BlockEntityTagQueryPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
