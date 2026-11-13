package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Entity-positioned value for a client debug subscription.
 *
 * <p>The entity ID refers to the source connection's entity namespace and the
 * update can either set or clear subscription data. Applying it to an observer
 * would require coordinated entity remapping and matching debug subscription
 * state, neither of which is part of scene playback.</p>
 */
final class Id028DebugEntityValuePacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x1C;
    static final String IDENTIFIER = "minecraft:debug/entity_value";

    Id028DebugEntityValuePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
