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
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:set_border_warning_distance}.
 *
 * <p>The nonnegative warning distance in blocks is shared world-border state
 * and remains eligible for global-state checkpoints.</p>
 */
final class Id094SetBorderWarningDistancePacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundSetBorderWarningDistancePacket,
        Id094SetBorderWarningDistancePacketDefinition.Value> {

    static final int PACKET_ID = 0x5E;
    static final String IDENTIFIER = "minecraft:set_border_warning_distance";

    Id094SetBorderWarningDistancePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetBorderWarningDistancePacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.GLOBAL_STATE);
    }

    @Override
    protected Value read(ClientboundSetBorderWarningDistancePacket packet) {
        return new Value(packet.getWarningBlocks());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
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

    /** One packet object for every player of the world. */
    @Override
    public RecipientCopies recipientCopies() {
        return RecipientCopies.identicalPayload();
    }
}
