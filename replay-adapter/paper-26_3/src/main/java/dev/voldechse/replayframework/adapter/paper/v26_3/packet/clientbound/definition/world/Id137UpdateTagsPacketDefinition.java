package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Complete native-codec pass-through for {@code minecraft:update_tags}.
 *
 * <p>Tags are server configuration, sent at login and after a reload, not
 * part of the scene. Replaying them would replace the viewer's live tags
 * with those of the recording time, and nothing restores them after the
 * playback. The packet therefore stays with its recipient.</p>
 */
final class Id137UpdateTagsPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x89;
    static final String IDENTIFIER = "minecraft:update_tags";

    Id137UpdateTagsPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
