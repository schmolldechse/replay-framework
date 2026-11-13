package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Connection-correlated request for the client cookie stored under one identifier.
 *
 * <p>The client answers with the same key and either no value or a bounded binary
 * payload. Replaying a recorded request could therefore disclose observer-owned
 * persisted data or interfere with a live server-side request awaiting that key.
 * It is captured for fidelity but never decoded or emitted during playback.</p>
 */
final class Id021CookieRequestPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x15;
    static final String IDENTIFIER = "minecraft:cookie_request";

    Id021CookieRequestPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
