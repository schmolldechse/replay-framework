package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:set_border_warning_delay}.
 *
 * <p>The nonnegative delay is shared world-border state, stored as ticks by
 * Paper 26.2 and eligible for global-state checkpoints.</p>
 */
final class Id091SetBorderWarningDelayPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSetBorderWarningDelayPacket,
        Id091SetBorderWarningDelayPacketDefinition.Value> {

    static final int PACKET_ID = 0x5B;
    static final String IDENTIFIER = "minecraft:set_border_warning_delay";

    Id091SetBorderWarningDelayPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetBorderWarningDelayPacket.class);
    }

    @Override
    protected Value read(ClientboundSetBorderWarningDelayPacket packet) {
        return new Value(packet.getWarningDelay());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundSetBorderWarningDelayPacket rebuild(Value value) {
        // Paper exposes only a WorldBorder constructor; decode this packet's
        // exact VarInt field rather than sourcing unrelated live border state.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(value.warningDelay());
            return ClientboundSetBorderWarningDelayPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    record Value(int warningDelay) {

        Value {
            if (warningDelay < 0) {
                throw new IncompatibleAdapterException(
                        "set_border_warning_delay must not be negative");
            }
        }
    }
}
