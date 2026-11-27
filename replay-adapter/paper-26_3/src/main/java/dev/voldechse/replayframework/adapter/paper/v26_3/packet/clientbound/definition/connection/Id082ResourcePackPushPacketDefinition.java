package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Captured connection-control contract for {@code minecraft:resource_pack_push}.
 *
 * <p>The packet can trigger a download, display a client prompt, and replace
 * active client resource assets. Its required flag can also affect whether the
 * client may decline without disconnecting. Replaying it would start a new
 * resource-pack interaction on the observer's connection, so it is retained
 * for fidelity and diagnostics but blocked from playback.</p>
 */
final class Id082ResourcePackPushPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x52;
    static final String IDENTIFIER = "minecraft:resource_pack_push";

    Id082ResourcePackPushPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
