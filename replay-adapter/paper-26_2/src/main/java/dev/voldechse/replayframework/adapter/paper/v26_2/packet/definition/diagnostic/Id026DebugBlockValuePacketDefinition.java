package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Block-positioned value for a client debug subscription.
 *
 * <p>The update contains a subscription-specific value or an absent value that
 * clears the corresponding debug entry. Its content can be registry-bound and
 * belongs to the receiving client's active debug UI; forwarding it during
 * replay could overwrite viewer-owned diagnostics or expose server internals.</p>
 */
final class Id026DebugBlockValuePacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x1A;
    static final String IDENTIFIER = "minecraft:debug/block_value";

    Id026DebugBlockValuePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
