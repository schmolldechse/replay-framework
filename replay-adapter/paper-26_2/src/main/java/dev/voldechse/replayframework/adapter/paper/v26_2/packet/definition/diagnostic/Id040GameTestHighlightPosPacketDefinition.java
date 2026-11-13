package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Blocks {@code minecraft:game_test_highlight_pos} from replay.
 *
 * <p>The packet is sent by the GameTest {@code showPos} developer command and
 * contains the selected block's absolute position plus its position relative
 * to a particular test structure. A replay observer may not have that test
 * structure or its debug context, so these coordinates do not identify a
 * generally meaningful replay target. The raw packet remains available in
 * recordings, but playback is blocked before its payload is decoded.</p>
 */
final class Id040GameTestHighlightPosPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x28;
    static final String IDENTIFIER = "minecraft:game_test_highlight_pos";

    Id040GameTestHighlightPosPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
