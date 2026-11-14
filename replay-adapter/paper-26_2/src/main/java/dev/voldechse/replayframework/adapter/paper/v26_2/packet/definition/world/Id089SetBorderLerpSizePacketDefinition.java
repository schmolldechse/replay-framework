package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:set_border_lerp_size}.
 *
 * <p>The packet changes shared world-border size over time. Its previous size,
 * target size, and remaining interpolation ticks contain no playback identity
 * and remain eligible for global-state checkpoints.</p>
 */
final class Id089SetBorderLerpSizePacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSetBorderLerpSizePacket,
        Id089SetBorderLerpSizePacketDefinition.Value> {

    static final int PACKET_ID = 0x59;
    static final String IDENTIFIER = "minecraft:set_border_lerp_size";

    Id089SetBorderLerpSizePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetBorderLerpSizePacket.class);
    }

    @Override
    protected Value read(ClientboundSetBorderLerpSizePacket packet) {
        return new Value(packet.getOldSize(), packet.getNewSize(), packet.getLerpTime());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundSetBorderLerpSizePacket rebuild(Value value) {
        // Paper exposes only a WorldBorder constructor; decode this packet's
        // exact native fields rather than sourcing unrelated live border state.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeDouble(value.oldSize());
            buffer.writeDouble(value.newSize());
            buffer.writeVarLong(value.lerpTime());
            return ClientboundSetBorderLerpSizePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    record Value(double oldSize, double newSize, long lerpTime) {

        Value {
            requireSize(oldSize, "oldSize");
            requireSize(newSize, "newSize");
            if (lerpTime < 0L) {
                throw new IncompatibleAdapterException(
                        "set_border_lerp_size lerpTime must not be negative");
            }
            if (lerpTime == 0L && oldSize != newSize) {
                throw new IncompatibleAdapterException(
                        "set_border_lerp_size cannot interpolate distinct sizes with zero duration");
            }
        }

        private static void requireSize(double value, String field) {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IncompatibleAdapterException(
                        "set_border_lerp_size " + field + " must be finite and non-negative");
            }
        }
    }
}
