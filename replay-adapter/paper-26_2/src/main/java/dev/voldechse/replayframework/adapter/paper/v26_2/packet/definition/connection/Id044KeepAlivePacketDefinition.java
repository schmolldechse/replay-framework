package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Connection-local keep-alive challenge for {@code minecraft:keep_alive}.
 *
 * <p>The 64-bit ID must match a challenge currently pending on the live
 * connection. Replaying a recorded challenge could interfere with its
 * connection state or provoke a timeout, so retain the frame for diagnostics
 * and block it before playback decoding.</p>
 */
final class Id044KeepAlivePacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x2C;
    static final String IDENTIFIER = "minecraft:keep_alive";

    Id044KeepAlivePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
