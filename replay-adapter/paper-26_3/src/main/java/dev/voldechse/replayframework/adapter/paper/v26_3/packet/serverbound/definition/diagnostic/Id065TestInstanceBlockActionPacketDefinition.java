package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:test_instance_block_action}.
 *
 * Operates a test instance block. Its block entity data is sent as a
 * clientbound packet.
 */
final class Id065TestInstanceBlockActionPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x41;
    static final String IDENTIFIER = "minecraft:test_instance_block_action";

    Id065TestInstanceBlockActionPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
