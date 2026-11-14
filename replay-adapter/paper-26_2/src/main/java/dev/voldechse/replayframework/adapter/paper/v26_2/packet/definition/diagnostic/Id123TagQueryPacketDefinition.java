package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Captured response to a client-originated block-entity or entity tag query.
 *
 * <p>The response transaction ID belongs to the requesting client connection. Replaying its
 * nullable NBT result to an observer has no matching request and could apply connection-specific
 * debug data to the wrong client, so this packet remains available only in the recording.</p>
 */
final class Id123TagQueryPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x7B;
    static final String IDENTIFIER = "minecraft:tag_query";

    Id123TagQueryPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
