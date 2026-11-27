package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Captured control contract for the PLAY clientbound
 * {@code minecraft:disconnect} packet.
 *
 * <p>Paper 26.3 carries one required chat component as its disconnect reason.
 * Handling it closes the receiving connection, so a recorded instance must
 * never be sent to the live playback observer. It is retained for recording
 * fidelity and diagnostics, then blocked before playback decoding.</p>
 */
final class Id032DisconnectPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x20;
    static final String IDENTIFIER = "minecraft:disconnect";

    Id032DisconnectPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
