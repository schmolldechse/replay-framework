package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Connection-local keep-alive challenge for {@code minecraft:keep_alive}.
 *
 * <p>The 64-bit ID must match a challenge currently pending on the live
 * connection. Replaying a recorded challenge could interfere with its
 * connection state or provoke a timeout, so retain the frame for diagnostics
 * and block it before playback decoding.</p>
 */
final class Id045KeepAlivePacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x2D;
    static final String IDENTIFIER = "minecraft:keep_alive";

    Id045KeepAlivePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
