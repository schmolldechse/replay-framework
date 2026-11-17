package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import java.util.UUID;

/**
 * Contract for {@code minecraft:set_chunk_cache_radius}.
 *
 * <p>The radius decides how far around its center a client keeps chunks. Like
 * the center it belongs to the receiving client: recorded copies are dropped,
 * and during playback the viewer keeps the radius its own server sends.</p>
 */
final class Id095SetChunkCacheRadiusPacketDefinition extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x5F;
    static final String IDENTIFIER = "minecraft:set_chunk_cache_radius";

    Id095SetChunkCacheRadiusPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

    /** The view radius decides which chunks withheld during a death teardown are sent again. */
    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        ClientboundSetChunkCacheRadiusPacket packet = ClientboundSetChunkCacheRadiusPacket.class.cast(decodedPacket);
        state.viewTeardown().viewRadius(connectionId, packet.getRadius());
        return super.captureContext(decodedPacket, descriptor, connectionId, state, serverTick);
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.VIEW_LOCAL;
    }
}
