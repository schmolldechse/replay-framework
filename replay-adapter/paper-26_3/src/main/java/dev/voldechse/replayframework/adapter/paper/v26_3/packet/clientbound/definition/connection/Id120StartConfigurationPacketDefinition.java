package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Captured connection-control contract for {@code minecraft:start_configuration}.
 *
 * <p>The terminal, payload-free packet switches an established PLAY connection into
 * CONFIGURATION. Replaying it would start a configuration handshake on the observer's live
 * connection, so retain it for recording fidelity and diagnostics but block it from playback.</p>
 */
final class Id120StartConfigurationPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x78;
    static final String IDENTIFIER = "minecraft:start_configuration";

    Id120StartConfigurationPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
