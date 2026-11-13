package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Captured control contract for the PLAY clientbound
 * {@code minecraft:disconnect} packet.
 *
 * <p>Paper 26.2 carries one required chat component as its disconnect reason.
 * Handling it closes the receiving connection, so a recorded instance must
 * never be sent to the live playback observer. It is retained for recording
 * fidelity and diagnostics, then blocked before playback decoding.</p>
 */
final class Id032DisconnectPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x20;
    static final String IDENTIFIER = "minecraft:disconnect";

    Id032DisconnectPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
