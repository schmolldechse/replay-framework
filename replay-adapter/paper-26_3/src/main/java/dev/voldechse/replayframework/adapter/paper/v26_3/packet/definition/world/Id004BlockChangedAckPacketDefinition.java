package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.world;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Connection-local cumulative prediction acknowledgement for
 * {@code minecraft:block_changed_ack}.
 *
 * <p>The sequence identifies predictions created by the receiving client's
 * own block interactions. It carries neither block state nor an acceptance
 * verdict. Replaying a recorded sequence could finish unrelated predictions
 * on the neutral observer and restore their retained block or player state,
 * so the frame remains captured control data but never enters playback.</p>
 */
final class Id004BlockChangedAckPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x04;
    static final String IDENTIFIER = "minecraft:block_changed_ack";

    Id004BlockChangedAckPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
