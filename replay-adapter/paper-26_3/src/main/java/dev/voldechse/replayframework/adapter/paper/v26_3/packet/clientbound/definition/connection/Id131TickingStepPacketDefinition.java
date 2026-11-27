package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Captures the server command that manually advances client processing by a number of ticks.
 *
 * <p>It only has an effect while the recipient's client ticking is frozen, so replaying it could
 * advance the observing client's own simulation rather than restore recorded scene state.</p>
 */
final class Id131TickingStepPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x83;
    static final String IDENTIFIER = "minecraft:ticking_step";

    Id131TickingStepPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
