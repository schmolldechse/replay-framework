package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import java.util.UUID;

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

    /** The view center decides which chunks withheld during a death teardown are sent again. */
    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        ClientboundSetChunkCacheCenterPacket packet = ClientboundSetChunkCacheCenterPacket.class.cast(decodedPacket);
        state.viewTeardown().viewCenter(connectionId, packet.getX(), packet.getZ());
        return super.captureContext(decodedPacket, descriptor, connectionId, state, serverTick);
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.VIEW_LOCAL;
    }
}
