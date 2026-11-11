package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
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
        Paper26_3ReplayPacketRewriter,
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
    protected Value read(ClientboundBlockEntityDataPacket packet) {
        return new Value(packet.getPos(), packet.getType(), packet.getTag());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
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
}
