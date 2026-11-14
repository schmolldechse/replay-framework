package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Captured connection-control contract for {@code minecraft:resource_pack_push}.
 *
 * <p>The packet can trigger a download, display a client prompt, and replace
 * active client resource assets. Its required flag can also affect whether the
 * client may decline without disconnecting. Replaying it would start a new
 * resource-pack interaction on the observer's connection, so it is retained
 * for fidelity and diagnostics but blocked from playback.</p>
 */
final class Id081ResourcePackPushPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x51;
    static final String IDENTIFIER = "minecraft:resource_pack_push";

    Id081ResourcePackPushPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
