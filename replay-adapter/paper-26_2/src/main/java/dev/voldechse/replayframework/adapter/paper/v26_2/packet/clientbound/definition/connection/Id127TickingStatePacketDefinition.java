package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Captures the server's client-tick-rate and frozen-state control update.
 *
 * <p>Replaying it would change the observing client's own processing cadence and could freeze
 * that client instead of restoring state from the recording.</p>
 */
final class Id127TickingStatePacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x7F;
    static final String IDENTIFIER = "minecraft:ticking_state";

    Id127TickingStatePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
