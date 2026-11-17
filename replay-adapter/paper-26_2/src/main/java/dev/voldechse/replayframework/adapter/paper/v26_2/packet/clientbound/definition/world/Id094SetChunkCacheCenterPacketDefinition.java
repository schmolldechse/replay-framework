package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import java.util.UUID;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:set_chunk_cache_center}.
 *
 * <p>The signed chunk X and Z coordinates set the receiving client's chunk
 * cache center. Preserve them verbatim as shared replay scene state.</p>
 */
final class Id094SetChunkCacheCenterPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSetChunkCacheCenterPacket,
        Id094SetChunkCacheCenterPacketDefinition.Value> {

    static final int PACKET_ID = 0x5E;
    static final String IDENTIFIER = "minecraft:set_chunk_cache_center";

    Id094SetChunkCacheCenterPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetChunkCacheCenterPacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.GLOBAL_STATE);
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
    protected Value read(ClientboundSetChunkCacheCenterPacket packet) {
        return new Value(packet.getX(), packet.getZ());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundSetChunkCacheCenterPacket rebuild(Value value) {
        return new ClientboundSetChunkCacheCenterPacket(value.x(), value.z());
    }

    record Value(int x, int z) {}

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.VIEW_LOCAL;
    }
}
