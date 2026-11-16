package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2CaptureContexts;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:block_update}.
 *
 * <p>The state is a canonical entry in the global block-state palette, not
 * merely a block type. It carries every property combination relevant to the
 * client, including orientation, shape, level, age and boolean properties.
 * Playback therefore preserves the registered state object exactly instead
 * of reconstructing it from a block name or the current server world.</p>
 */
final class Id008BlockUpdatePacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundBlockUpdatePacket,
        Id008BlockUpdatePacketDefinition.Value> {

    static final int PACKET_ID = 0x08;
    static final String IDENTIFIER = "minecraft:block_update";

    Id008BlockUpdatePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundBlockUpdatePacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.PacketCheckpointFamily.BLOCK_ENTITY);
    }

    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            java.util.UUID connectionId,
            dev.voldechse.replayframework.adapter.paper.packet.PacketCaptureState state,
            long serverTick) {
        CaptureContext base = super.captureContext(
                decodedPacket, descriptor, connectionId, state, serverTick);
        return Paper26_2CaptureContexts.atBlockPosition(
                base, toApiPosition(ClientboundBlockUpdatePacket.class.cast(decodedPacket).getPos()));
    }

    @Override
    protected Value read(ClientboundBlockUpdatePacket packet) {
        return new Value(packet.getPos(), packet.getBlockState());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        // A block state contains no playback identity. Resolving it from the
        // live world would replace recorded state with unrelated current data.
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundBlockUpdatePacket rebuild(Value value) {
        return new ClientboundBlockUpdatePacket(value.position(), value.blockState());
    }

    record Value(BlockPos position, BlockState blockState) {

        Value {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(blockState, "blockState");
            if (Block.BLOCK_STATE_REGISTRY.getId(blockState) < 0) {
                throw new IncompatibleAdapterException(
                        "block_update state is not in the global block-state palette");
            }
        }
    }

    private static dev.voldechse.replayframework.api.recording.BlockPosition toApiPosition(
            BlockPos position) {
        return new dev.voldechse.replayframework.api.recording.BlockPosition(
                position.getX(), position.getY(), position.getZ());
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.PacketDistribution.BROADCAST;
    }
}
