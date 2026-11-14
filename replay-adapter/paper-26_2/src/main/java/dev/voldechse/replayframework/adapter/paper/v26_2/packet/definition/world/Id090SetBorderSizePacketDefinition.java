package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:set_border_size}.
 *
 * <p>The packet snaps the shared world border to one diameter immediately. Its
 * single value has no playback identity and remains eligible for global-state
 * checkpoints.</p>
 */
final class Id090SetBorderSizePacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSetBorderSizePacket,
        Id090SetBorderSizePacketDefinition.Value> {

    static final int PACKET_ID = 0x5A;
    static final String IDENTIFIER = "minecraft:set_border_size";

    Id090SetBorderSizePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetBorderSizePacket.class);
    }

    @Override
    protected Value read(ClientboundSetBorderSizePacket packet) {
        return new Value(packet.getSize());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundSetBorderSizePacket rebuild(Value value) {
        // Paper exposes only a WorldBorder constructor; decode the exact
        // one-double packet shape instead of sourcing unrelated border state.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeDouble(value.size());
            return ClientboundSetBorderSizePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    record Value(double size) {

        Value {
            if (!Double.isFinite(size) || size < 0.0) {
                throw new IncompatibleAdapterException(
                        "set_border_size size must be finite and non-negative");
            }
        }
    }
}
