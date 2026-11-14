package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;

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
}
