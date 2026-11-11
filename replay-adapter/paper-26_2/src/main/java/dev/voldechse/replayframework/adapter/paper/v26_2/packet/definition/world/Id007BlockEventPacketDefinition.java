package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.world.level.block.Block;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:block_event}.
 *
 * <p>Action ID and action parameter are unsigned bytes whose meaning is
 * selected by the block state currently present at {@link Value#position()}.
 * Vanilla uses different pairs for note blocks, pistons, containers, bells,
 * decorated pots and other blocks, while custom or future blocks may define
 * additional pairs. The definition therefore preserves every unsigned value
 * and the complete registered block reference instead of imposing a global
 * action enum or allowlist. Vanilla currently dispatches against the live
 * block state and does not use the packet's block reference, but that wire
 * field is retained for protocol fidelity and non-vanilla compatibility.</p>
 */
final class Id007BlockEventPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundBlockEventPacket,
        Id007BlockEventPacketDefinition.Value> {

    static final int PACKET_ID = 0x07;
    static final String IDENTIFIER = "minecraft:block_event";

    Id007BlockEventPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundBlockEventPacket.class);
    }

    @Override
    protected Value read(ClientboundBlockEventPacket packet) {
        // The native constructor accepts ints, but the wire codec writes one
        // byte per action field and decodes both as unsigned. Canonicalizing
        // also covers packets constructed in memory with non-wire values.
        return new Value(
                packet.getPos(),
                packet.getB0() & 0xFF,
                packet.getB1() & 0xFF,
                packet.getBlock());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        // The packet contains no entity or profile identity. Its meaning must
        // remain tied to the captured position, block and opaque action pair.
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundBlockEventPacket rebuild(Value value) {
        return new ClientboundBlockEventPacket(
                value.position(), value.block(), value.actionId(), value.actionParameter());
    }

    record Value(BlockPos position, int actionId, int actionParameter, Block block) {

        Value {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(block, "block");
            requireUnsignedByte("action ID", actionId);
            requireUnsignedByte("action parameter", actionParameter);
        }

        private static void requireUnsignedByte(String field, int value) {
            if (value < 0 || value > 0xFF) {
                throw new IncompatibleAdapterException(
                        "block_event " + field + " is not an unsigned byte: " + value);
            }
        }
    }
}
