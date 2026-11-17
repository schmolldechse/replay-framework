package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import java.util.UUID;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:set_chunk_cache_radius}.
 *
 * <p>The VarInt is the server-imposed chunk view distance. Preserve the native
 * integer as shared replay state without substituting live server settings.</p>
 */
final class Id095SetChunkCacheRadiusPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSetChunkCacheRadiusPacket,
        Id095SetChunkCacheRadiusPacketDefinition.Value> {

    static final int PACKET_ID = 0x5F;
    static final String IDENTIFIER = "minecraft:set_chunk_cache_radius";

    Id095SetChunkCacheRadiusPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetChunkCacheRadiusPacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.GLOBAL_STATE);
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
    protected Value read(ClientboundSetChunkCacheRadiusPacket packet) {
        return new Value(packet.getRadius());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundSetChunkCacheRadiusPacket rebuild(Value value) {
        return new ClientboundSetChunkCacheRadiusPacket(value.radius());
    }

    record Value(int radius) {}

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.VIEW_LOCAL;
    }
}
