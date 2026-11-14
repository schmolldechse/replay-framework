package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;

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
}
