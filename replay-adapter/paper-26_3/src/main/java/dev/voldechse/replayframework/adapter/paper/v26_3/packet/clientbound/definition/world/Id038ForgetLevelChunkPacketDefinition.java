package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.Objects;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.ChunkPos;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:forget_level_chunk}.
 *
 * <p>The packet unloads a chunk column from the receiving client's view. Paper
 * encodes its signed X/Z coordinates as one packed long. Preserve the native
 * {@link ChunkPos} exactly and send the recorded unload so stale terrain is
 * removed from the observer's scene; the packet contains no playback identity
 * and its position is also part of checkpointed chunk state.</p>
 */
final class Id038ForgetLevelChunkPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundForgetLevelChunkPacket,
        Id038ForgetLevelChunkPacketDefinition.Value> {

    static final int PACKET_ID = 0x26;
    static final String IDENTIFIER = "minecraft:forget_level_chunk";

    Id038ForgetLevelChunkPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundForgetLevelChunkPacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.CHUNK_LIGHT);
    }

    @Override
    protected Value read(ClientboundForgetLevelChunkPacket packet) {
        return new Value(packet.pos());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        // Do not consult the live world or chunk tracker. The recorded packet
        // is the state change that must be applied to the observer's world.
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundForgetLevelChunkPacket rebuild(Value value) {
        return new ClientboundForgetLevelChunkPacket(value.position());
    }

    record Value(ChunkPos position) {

        Value {
            Objects.requireNonNull(position, "position");
        }
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.VIEW_MEMBERSHIP;
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.SceneMembership sceneMembership(Object decodedPacket) {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.SceneMembership.chunkLost(net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket.class.cast(decodedPacket).pos().x(), net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket.class.cast(decodedPacket).pos().z());
    }
}
