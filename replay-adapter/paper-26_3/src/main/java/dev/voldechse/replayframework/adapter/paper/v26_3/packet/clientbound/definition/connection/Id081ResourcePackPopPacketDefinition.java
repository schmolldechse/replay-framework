package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Captured control contract for {@code minecraft:resource_pack_pop}.
 *
 * <p>The optional UUID removes one server-provided resource pack when present,
 * and removes all server-provided packs when absent. Applying either form
 * changes the receiving client's active resource assets, so recorded packets
 * are retained for fidelity and diagnostics but blocked from playback.</p>
 */
final class Id081ResourcePackPopPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x51;
    static final String IDENTIFIER = "minecraft:resource_pack_pop";

    Id081ResourcePackPopPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
