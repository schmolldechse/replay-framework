package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

/**
 * Complete chunk terrain, block entity, heightmap and light state for
 * {@code minecraft:level_chunk_with_light}.
 *
 * <p>Paper's native codec owns the version-specific section palettes, NBT,
 * registry IDs and light masks. Since playback does not rewrite any of those
 * fields, retain the verified native packet intact rather than rebuilding it
 * from partial public accessors.</p>
 */
final class Id045LevelChunkWithLightPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x2D;
    static final String IDENTIFIER = "minecraft:level_chunk_with_light";

    Id045LevelChunkWithLightPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE));
    }

    @Override
    protected PacketRewriteResult rewriteSafe(
            Object decodedPacket,
            Paper26_2ReplayPacketRewriter context) {
        if (!(decodedPacket instanceof ClientboundLevelChunkWithLightPacket packet)) {
            throw new IncompatibleAdapterException(
                    "decoded type for " + typeName() + " was "
                            + decodedPacket.getClass().getName()
                            + ", expected " + ClientboundLevelChunkWithLightPacket.class.getName());
        }
        return PacketRewriteResult.send(packet);
    }
}
