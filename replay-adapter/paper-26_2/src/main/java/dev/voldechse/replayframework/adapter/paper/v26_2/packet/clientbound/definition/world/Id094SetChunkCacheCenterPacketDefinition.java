package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Contract for {@code minecraft:set_chunk_cache_center}.
 *
 * <p>The center decides which chunks a client keeps. It belongs to the view of
 * the client that receives it: the recorded copy describes the recorded
 * connection, and during playback the viewer's own server sends the viewer's
 * center as the viewer moves. Recorded copies are therefore dropped, and the
 * playback gate lets the viewer's live packets through.</p>
 */
final class Id094SetChunkCacheCenterPacketDefinition extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x5E;
    static final String IDENTIFIER = "minecraft:set_chunk_cache_center";

    Id094SetChunkCacheCenterPacketDefinition() {
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
