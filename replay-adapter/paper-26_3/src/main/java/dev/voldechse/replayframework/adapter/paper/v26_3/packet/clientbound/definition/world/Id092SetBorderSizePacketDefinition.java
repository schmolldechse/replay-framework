package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientCopies;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
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
final class Id092SetBorderSizePacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundSetBorderSizePacket,
        Id092SetBorderSizePacketDefinition.Value> {

    static final int PACKET_ID = 0x5C;
    static final String IDENTIFIER = "minecraft:set_border_size";

    Id092SetBorderSizePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetBorderSizePacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.GLOBAL_STATE);
    }

    @Override
    protected Value read(ClientboundSetBorderSizePacket packet) {
        return new Value(packet.getSize());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
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

    /** One packet object for every player of the world. */
    @Override
    public RecipientCopies recipientCopies() {
        return RecipientCopies.identicalPayload();
    }
}
