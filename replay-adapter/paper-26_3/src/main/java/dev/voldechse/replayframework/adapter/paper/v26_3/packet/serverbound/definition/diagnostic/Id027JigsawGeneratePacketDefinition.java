package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:jigsaw_generate}.
 *
 * Generates a jigsaw structure. The placed blocks are sent as clientbound
 * block updates.
 */
final class Id027JigsawGeneratePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x1B;
    static final String IDENTIFIER = "minecraft:jigsaw_generate";

    Id027JigsawGeneratePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
