package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Captured connection-control contract for {@code minecraft:store_cookie}.
 *
 * <p>The key and bounded byte payload modify the receiving client's persistent cookie store.
 * Retain the frame for recording fidelity and diagnostics, but never apply another connection's
 * cookie data to the replay observer.</p>
 */
final class Id120StoreCookiePacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x78;
    static final String IDENTIFIER = "minecraft:store_cookie";

    Id120StoreCookiePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
