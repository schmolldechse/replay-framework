package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.CaptureContexts;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddTransientBlockPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:add_transient_block}.
 *
 * <p>Paper 26.3 sends it, together with a {@code block_update}, to the
 * trackers of a falling block that cannot place itself: the client shows the
 * block state at the position briefly. It changes no world state, so it is an
 * ephemeral effect at its block. Position and state carry no playback
 * identity and are replayed as recorded.</p>
 */
final class Id037AddTransientBlockPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundAddTransientBlockPacket,
        Id037AddTransientBlockPacketDefinition.Value> {

    static final int PACKET_ID = 0x25;
    static final String IDENTIFIER = "minecraft:add_transient_block";

    Id037AddTransientBlockPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundAddTransientBlockPacket.class);
    }

    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        CaptureContext base = super.captureContext(
                decodedPacket, descriptor, connectionId, state, serverTick);
        BlockPos position = ClientboundAddTransientBlockPacket.class.cast(decodedPacket).getPos();
        return CaptureContexts.atBlockPosition(
                base, new BlockPosition(position.getX(), position.getY(), position.getZ()));
    }

    @Override
    protected Value read(ClientboundAddTransientBlockPacket packet) {
        return new Value(packet.getPos(), packet.getBlockState());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundAddTransientBlockPacket rebuild(Value value) {
        return new ClientboundAddTransientBlockPacket(value.position(), value.blockState());
    }

    record Value(BlockPos position, BlockState blockState) {

        Value {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(blockState, "blockState");
            if (Block.BLOCK_STATE_REGISTRY.getId(blockState) < 0) {
                throw new IncompatibleAdapterException(
                        "add_transient_block state is not in the global block-state palette");
            }
        }
    }

    @Override
    public PacketDistribution distribution() {
        return PacketDistribution.BROADCAST;
    }
}
