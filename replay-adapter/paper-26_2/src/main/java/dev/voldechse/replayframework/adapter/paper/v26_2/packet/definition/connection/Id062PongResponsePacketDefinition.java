package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Client-session ping result for {@code minecraft:pong_response}.
 *
 * <p>The response echoes a client-generated 64-bit ping time. It belongs to
 * the observer's own pending latency sample and must not be replayed from a
 * different connection.</p>
 */
final class Id062PongResponsePacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x3E;
    static final String IDENTIFIER = "minecraft:pong_response";

    Id062PongResponsePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
