package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Captured status update for the recipient's open test-instance block editor.
 *
 * <p>The status component and optional structure size belong to GameTest authoring UI and its
 * active test context. That context does not exist in general scene playback, so replay is blocked
 * while the original frame remains available for diagnostics.</p>
 */
final class Id126TestInstanceBlockStatusPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x7E;
    static final String IDENTIFIER = "minecraft:test_instance_block_status";

    Id126TestInstanceBlockStatusPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
