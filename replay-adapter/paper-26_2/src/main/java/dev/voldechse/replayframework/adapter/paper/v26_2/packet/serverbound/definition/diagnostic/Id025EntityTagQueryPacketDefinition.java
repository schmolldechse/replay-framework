package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:entity_tag_query}.
 *
 * Debug query for entity data. Changes nothing.
 */
final class Id025EntityTagQueryPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x19;
    static final String IDENTIFIER = "minecraft:entity_tag_query";

    Id025EntityTagQueryPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
