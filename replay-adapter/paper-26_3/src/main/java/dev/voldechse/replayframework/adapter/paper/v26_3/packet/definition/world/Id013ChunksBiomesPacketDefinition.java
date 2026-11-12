package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.world;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.world.level.ChunkPos;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:chunks_biomes}.
 *
 * <p>Each entry replaces the serialized biome palettes for one chunk. The
 * buffer is deliberately kept opaque: decoding it requires the target
 * dimension's section count and the synchronized biome registry. Rebuilding
 * those bytes from the live server would replace recorded state with current
 * world state. Lists and buffers are copied deeply because the native packet
 * exposes mutable {@code byte[]} values directly.</p>
 */
final class Id013ChunksBiomesPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundChunksBiomesPacket,
        Id013ChunksBiomesPacketDefinition.Value> {

    static final int PACKET_ID = 0x0D;
    static final String IDENTIFIER = "minecraft:chunks_biomes";
    static final int MAX_BIOME_DATA_SIZE = 2_097_152;

    Id013ChunksBiomesPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundChunksBiomesPacket.class);
    }

    @Override
    protected Value read(ClientboundChunksBiomesPacket packet) {
        return new Value(packet.chunkBiomeData().stream()
                .map(chunk -> new ChunkBiomeValue(chunk.pos(), chunk.buffer()))
                .toList());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        // Biome palette ids are versioned registry content, not playback
        // identities. Preserve the recorded buffers instead of resolving the
        // corresponding chunks or biomes from the live server.
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundChunksBiomesPacket rebuild(Value value) {
        return new ClientboundChunksBiomesPacket(value.chunks().stream()
                .map(chunk -> new ClientboundChunksBiomesPacket.ChunkBiomeData(
                        chunk.position(), chunk.buffer()))
                .toList());
    }

    record Value(List<ChunkBiomeValue> chunks) {

        Value {
            chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        }
    }

    record ChunkBiomeValue(ChunkPos position, byte[] buffer) {

        ChunkBiomeValue {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(buffer, "buffer");
            if (buffer.length > MAX_BIOME_DATA_SIZE) {
                throw new IncompatibleAdapterException(
                        "chunks_biomes data exceeds the native 2 MiB entry limit");
            }
            buffer = buffer.clone();
        }

        @Override
        public byte[] buffer() {
            return buffer.clone();
        }
    }
}
