package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * One-shot event emitted by a registered client debug subscription.
 *
 * <p>Unlike a value update, the subscription event carries a required,
 * type-dependent payload. It may refer to dynamic registries, world positions,
 * or live diagnostic state, and is intended for the receiving client's debug
 * renderers rather than replay scene state.</p>
 */
final class Id029DebugEventPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x1D;
    static final String IDENTIFIER = "minecraft:debug/event";

    Id029DebugEventPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
