package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Captures the server command that manually advances client processing by a number of ticks.
 *
 * <p>It only has an effect while the recipient's client ticking is frozen, so replaying it could
 * advance the observing client's own simulation rather than restore recorded scene state.</p>
 */
final class Id128TickingStepPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x80;
    static final String IDENTIFIER = "minecraft:ticking_step";

    Id128TickingStepPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
