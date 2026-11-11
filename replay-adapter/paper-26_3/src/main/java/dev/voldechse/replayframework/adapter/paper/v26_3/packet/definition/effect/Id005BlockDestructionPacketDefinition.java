package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.effect;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:block_destruction}.
 *
 * <p>The ID is the client-side overlay key. Vanilla normally uses the breaking
 * entity ID, but Paper also permits IDs without a corresponding entity. It is
 * therefore mapped into the playback session's isolated entity-ID space. Wire
 * progress values {@code 0..9} update an overlay and {@code 10..255} remove it;
 * every value is preserved exactly.</p>
 */
final class Id005BlockDestructionPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundBlockDestructionPacket,
        Id005BlockDestructionPacketDefinition.Value> {

    static final int PACKET_ID = 0x05;
    static final String IDENTIFIER = "minecraft:block_destruction";

    Id005BlockDestructionPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundBlockDestructionPacket.class);
    }

    @Override
    protected Value read(ClientboundBlockDestructionPacket packet) {
        // The native constructor stores an int, but the wire codec writes one
        // byte and decodes it unsigned. Canonicalizing also covers packets
        // constructed in memory with values such as -1 or 256.
        return new Value(
                packet.getId(),
                packet.getPos(),
                packet.getProgress() & 0xFF);
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.sourceId()),
                value.position(),
                value.progress()));
    }

    @Override
    protected ClientboundBlockDestructionPacket rebuild(Value value) {
        return new ClientboundBlockDestructionPacket(
                value.sourceId(), value.position(), value.progress());
    }

    record Value(int sourceId, BlockPos position, int progress) {

        Value {
            Objects.requireNonNull(position, "position");
            if (progress < 0 || progress > 0xFF) {
                throw new IncompatibleAdapterException(
                        "block_destruction progress is not an unsigned byte: " + progress);
            }
        }
    }
}
