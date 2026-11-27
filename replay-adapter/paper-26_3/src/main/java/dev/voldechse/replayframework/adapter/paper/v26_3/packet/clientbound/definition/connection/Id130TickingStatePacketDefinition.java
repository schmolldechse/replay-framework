package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Captures the server's client-tick-rate and frozen-state control update.
 *
 * <p>Replaying it would change the observing client's own processing cadence and could freeze
 * that client instead of restoring state from the recording.</p>
 */
final class Id130TickingStatePacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x82;
    static final String IDENTIFIER = "minecraft:ticking_state";

    Id130TickingStatePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
