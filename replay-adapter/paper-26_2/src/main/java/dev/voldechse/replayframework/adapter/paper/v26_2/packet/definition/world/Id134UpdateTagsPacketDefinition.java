package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Complete native-codec pass-through for {@code minecraft:update_tags}.
 *
 * <p>Registry entry IDs in tag payloads are meaningful only for the matching
 * registry contents and ordering. The packet has no entity references to
 * rewrite, so the safe policy preserves the complete version-bound packet and
 * relies on the replay environment's documented registry compatibility.</p>
 */
final class Id134UpdateTagsPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x86;
    static final String IDENTIFIER = "minecraft:update_tags";

    Id134UpdateTagsPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE));
    }
}
