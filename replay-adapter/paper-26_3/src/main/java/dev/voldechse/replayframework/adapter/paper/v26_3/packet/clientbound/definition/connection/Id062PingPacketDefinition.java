package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Connection-bound synchronization challenge for {@code minecraft:ping}.
 *
 * <p>The client echoes this packet's integer ID in a serverbound Pong. That
 * response must stay on the connection which issued the ping, so captured
 * frames are retained for diagnostics but blocked from playback.</p>
 */
final class Id062PingPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x3E;
    static final String IDENTIFIER = "minecraft:ping";

    Id062PingPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
