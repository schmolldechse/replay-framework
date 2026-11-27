package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Captured connection-control contract for {@code minecraft:store_cookie}.
 *
 * <p>The key and bounded byte payload modify the receiving client's persistent cookie store.
 * Retain the frame for recording fidelity and diagnostics, but never apply another connection's
 * cookie data to the replay observer.</p>
 */
final class Id122StoreCookiePacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x7A;
    static final String IDENTIFIER = "minecraft:store_cookie";

    Id122StoreCookiePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
