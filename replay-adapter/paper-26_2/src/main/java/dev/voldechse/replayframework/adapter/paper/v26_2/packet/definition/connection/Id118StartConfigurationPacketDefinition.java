package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Captured connection-control contract for {@code minecraft:start_configuration}.
 *
 * <p>The terminal, payload-free packet switches an established PLAY connection into
 * CONFIGURATION. Replaying it would start a configuration handshake on the observer's live
 * connection, so retain it for recording fidelity and diagnostics but block it from playback.</p>
 */
final class Id118StartConfigurationPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x76;
    static final String IDENTIFIER = "minecraft:start_configuration";

    Id118StartConfigurationPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
