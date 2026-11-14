package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:set_border_warning_distance}.
 *
 * <p>The nonnegative warning distance in blocks is shared world-border state
 * and remains eligible for global-state checkpoints.</p>
 */
final class Id092SetBorderWarningDistancePacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSetBorderWarningDistancePacket,
        Id092SetBorderWarningDistancePacketDefinition.Value> {

    static final int PACKET_ID = 0x5C;
    static final String IDENTIFIER = "minecraft:set_border_warning_distance";

    Id092SetBorderWarningDistancePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetBorderWarningDistancePacket.class);
    }

    @Override
    protected Value read(ClientboundSetBorderWarningDistancePacket packet) {
        return new Value(packet.getWarningBlocks());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundSetBorderWarningDistancePacket rebuild(Value value) {
        // Paper exposes only a WorldBorder constructor; decode this packet's
        // exact VarInt field rather than sourcing unrelated live border state.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(value.warningBlocks());
            return ClientboundSetBorderWarningDistancePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    record Value(int warningBlocks) {

        Value {
            if (warningBlocks < 0) {
                throw new IncompatibleAdapterException(
                        "set_border_warning_distance must not be negative");
            }
        }
    }
}
