package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Connection-bound synchronization challenge for {@code minecraft:ping}.
 *
 * <p>The client echoes this packet's integer ID in a serverbound Pong. That
 * response must stay on the connection which issued the ping, so captured
 * frames are retained for diagnostics but blocked from playback.</p>
 */
final class Id061PingPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x3D;
    static final String IDENTIFIER = "minecraft:ping";

    Id061PingPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
