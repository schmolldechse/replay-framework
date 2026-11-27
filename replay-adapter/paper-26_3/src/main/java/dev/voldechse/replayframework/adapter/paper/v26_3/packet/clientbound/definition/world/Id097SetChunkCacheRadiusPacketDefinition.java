package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Contract for {@code minecraft:set_chunk_cache_radius}.
 *
 * <p>The radius decides how far around its center a client keeps chunks. Like
 * the center it belongs to the receiving client: recorded copies are dropped,
 * and during playback the viewer keeps the radius its own server sends.</p>
 */
final class Id097SetChunkCacheRadiusPacketDefinition extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x61;
    static final String IDENTIFIER = "minecraft:set_chunk_cache_radius";

    Id097SetChunkCacheRadiusPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.VIEW_LOCAL;
    }
}
