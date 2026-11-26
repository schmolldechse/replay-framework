package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.CaptureContexts;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:block_entity_data}.
 *
 * <p>The tag schema depends entirely on the registered block-entity type and
 * is already sanitized by Paper before the packet reaches capture. Playback
 * therefore preserves a defensive copy instead of interpreting tag values as
 * identities or deriving a replacement from live world state.</p>
 */
final class Id006BlockEntityDataPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundBlockEntityDataPacket,
        Id006BlockEntityDataPacketDefinition.Value> {

    static final int PACKET_ID = 0x06;
    static final String IDENTIFIER = "minecraft:block_entity_data";

    Id006BlockEntityDataPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundBlockEntityDataPacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.BLOCK_ENTITY);
    }

    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            java.util.UUID connectionId,
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState state,
            long serverTick) {
        CaptureContext base = super.captureContext(
                decodedPacket, descriptor, connectionId, state, serverTick);
        return CaptureContexts.atBlockPosition(
                base, new dev.voldechse.replayframework.api.recording.BlockPosition(
                        ClientboundBlockEntityDataPacket.class.cast(decodedPacket).getPos().getX(),
                        ClientboundBlockEntityDataPacket.class.cast(decodedPacket).getPos().getY(),
                        ClientboundBlockEntityDataPacket.class.cast(decodedPacket).getPos().getZ()));
    }

    @Override
    protected Value read(ClientboundBlockEntityDataPacket packet) {
        return new Value(packet.getPos(), packet.getType(), packet.getTag());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        // The tag schema is selected by the block-entity type. UUID-, integer-
        // or position-shaped entries are block content, not verified playback
        // identities, so the complete opaque value must remain unchanged.
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundBlockEntityDataPacket rebuild(Value value) {
        return new ClientboundBlockEntityDataPacket(
                value.position(), value.type(), value.tag());
    }

    record Value(BlockPos position, BlockEntityType<?> type, CompoundTag tag) {

        Value {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(type, "type");
            tag = Objects.requireNonNull(tag, "tag").copy();
        }

        @Override
        public CompoundTag tag() {
            return tag.copy();
        }
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
    }
}
