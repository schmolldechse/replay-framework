package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:set_border_center}.
 *
 * <p>The packet updates shared world-border state with the center coordinates
 * X and Z. It has no playback identities, so finite coordinates are preserved
 * unchanged and remain eligible for global-state checkpoints.</p>
 */
final class Id088SetBorderCenterPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSetBorderCenterPacket,
        Id088SetBorderCenterPacketDefinition.Value> {

    static final int PACKET_ID = 0x58;
    static final String IDENTIFIER = "minecraft:set_border_center";

    Id088SetBorderCenterPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetBorderCenterPacket.class);
    }

    @Override
    protected Value read(ClientboundSetBorderCenterPacket packet) {
        return new Value(packet.getNewCenterX(), packet.getNewCenterZ());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundSetBorderCenterPacket rebuild(Value value) {
        // Paper exposes only a WorldBorder constructor; decode the exact
        // two-double wire shape instead of sourcing unrelated border state.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeDouble(value.centerX());
            buffer.writeDouble(value.centerZ());
            return ClientboundSetBorderCenterPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    record Value(double centerX, double centerZ) {

        Value {
            requireFinite(centerX, "centerX");
            requireFinite(centerZ, "centerZ");
        }

        private static void requireFinite(double value, String field) {
            if (!Double.isFinite(value)) {
                throw new IncompatibleAdapterException(
                        "set_border_center contains a non-finite " + field);
            }
        }
    }
}
