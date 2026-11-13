package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Chunk-positioned value for a client debug subscription.
 *
 * <p>The wire order is chunk Z followed by chunk X, matching the packed long
 * consumed by the client. Values are viewer debug state and may clear a prior
 * entry, so replay cannot safely restore them independently of the observer's
 * active subscriptions and current world.</p>
 */
final class Id027DebugChunkValuePacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x1B;
    static final String IDENTIFIER = "minecraft:debug/chunk_value";

    Id027DebugChunkValuePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
