package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Captured status update for the recipient's open test-instance block editor.
 *
 * <p>The status component and optional structure size belong to GameTest authoring UI and its
 * active test context. That context does not exist in general scene playback, so replay is blocked
 * while the original frame remains available for diagnostics.</p>
 */
final class Id129TestInstanceBlockStatusPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x81;
    static final String IDENTIFIER = "minecraft:test_instance_block_status";

    Id129TestInstanceBlockStatusPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
