package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Client-session ping result for {@code minecraft:pong_response}.
 *
 * <p>The response echoes a client-generated 64-bit ping time. It belongs to
 * the observer's own pending latency sample and must not be replayed from a
 * different connection.</p>
 */
final class Id063PongResponsePacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x3F;
    static final String IDENTIFIER = "minecraft:pong_response";

    Id063PongResponsePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
