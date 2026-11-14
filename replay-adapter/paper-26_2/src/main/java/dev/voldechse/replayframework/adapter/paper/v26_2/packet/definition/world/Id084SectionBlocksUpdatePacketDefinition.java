package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Complete read/rewrite/rebuild contract for {@code minecraft:section_blocks_update}. */
final class Id084SectionBlocksUpdatePacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSectionBlocksUpdatePacket,
        Id084SectionBlocksUpdatePacketDefinition.Value> {

    static final int PACKET_ID = 0x54;
    static final String IDENTIFIER = "minecraft:section_blocks_update";
    private static final int LOCAL_POSITION_MASK = 0xFFF;

    Id084SectionBlocksUpdatePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSectionBlocksUpdatePacket.class);
    }

    @Override
    protected Value read(ClientboundSectionBlocksUpdatePacket packet) {
        Objects.requireNonNull(packet, "packet");
        packet.runUpdates((ignoredPosition, state) -> requireRegisteredState(state));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.encode(buffer, packet);
            SectionPos sectionPos = SectionPos.STREAM_CODEC.decode(buffer);
            int updateCount = buffer.readVarInt();
            if (updateCount < 0) {
                throw new IncompatibleAdapterException(
                        "section_blocks_update has a negative update count");
            }

            List<BlockChange> changes = new ArrayList<>(updateCount);
            for (int index = 0; index < updateCount; index++) {
                long packedChange = buffer.readVarLong();
                long stateId = packedChange >>> 12;
                if (stateId > Integer.MAX_VALUE) {
                    throw new IncompatibleAdapterException(
                            "section_blocks_update state ID exceeds the native registry range");
                }
                BlockState state = Block.BLOCK_STATE_REGISTRY.byId((int) stateId);
                requireRegisteredState(state);
                changes.add(new BlockChange(
                        (int) (packedChange & LOCAL_POSITION_MASK), state));
            }
            if (buffer.isReadable()) {
                throw new IncompatibleAdapterException(
                        "section_blocks_update native codec left unread body bytes");
            }
            return new Value(sectionPos, changes);
        } finally {
            buffer.release();
        }
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundSectionBlocksUpdatePacket rebuild(Value value) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SectionPos.STREAM_CODEC.encode(buffer, value.sectionPos());
            buffer.writeVarInt(value.changes().size());
            for (BlockChange change : value.changes()) {
                requireRegisteredState(change.state());
                long packedChange = (long) Block.BLOCK_STATE_REGISTRY.getId(change.state()) << 12
                        | change.localPosition();
                buffer.writeVarLong(packedChange);
            }
            return ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void requireRegisteredState(BlockState state) {
        if (state == null || Block.BLOCK_STATE_REGISTRY.getId(state) < 0) {
            throw new IncompatibleAdapterException(
                    "section_blocks_update contains a block state outside the global palette");
        }
    }

    record Value(SectionPos sectionPos, List<BlockChange> changes) {

        Value {
            Objects.requireNonNull(sectionPos, "sectionPos");
            changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        }
    }

    record BlockChange(int localPosition, BlockState state) {

        BlockChange {
            if (localPosition < 0 || localPosition > LOCAL_POSITION_MASK) {
                throw new IllegalArgumentException(
                        "localPosition must fit in twelve bits");
            }
            Objects.requireNonNull(state, "state");
            requireRegisteredState(state);
        }
    }
}
