package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Captured control contract for {@code minecraft:resource_pack_pop}.
 *
 * <p>The optional UUID removes one server-provided resource pack when present,
 * and removes all server-provided packs when absent. Applying either form
 * changes the receiving client's active resource assets, so recorded packets
 * are retained for fidelity and diagnostics but blocked from playback.</p>
 */
final class Id080ResourcePackPopPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x50;
    static final String IDENTIFIER = "minecraft:resource_pack_pop";

    Id080ResourcePackPopPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
