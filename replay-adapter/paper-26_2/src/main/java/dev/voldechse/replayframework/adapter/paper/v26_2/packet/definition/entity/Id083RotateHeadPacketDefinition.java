package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;

/** Complete read/rewrite/rebuild contract for {@code minecraft:rotate_head}. */
final class Id083RotateHeadPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundRotateHeadPacket,
        Id083RotateHeadPacketDefinition.Value> {

    static final int PACKET_ID = 0x53;
    static final String IDENTIFIER = "minecraft:rotate_head";

    Id083RotateHeadPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundRotateHeadPacket.class);
    }

    @Override
    protected Value read(ClientboundRotateHeadPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundRotateHeadPacket.STREAM_CODEC.encode(buffer, packet);
            return new Value(buffer.readVarInt(), buffer.readByte());
        } finally {
            buffer.release();
        }
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()), value.headYaw()));
    }

    @Override
    protected ClientboundRotateHeadPacket rebuild(Value value) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(value.entityId());
            buffer.writeByte(value.headYaw());
            // The native packet only exposes an Entity-based constructor. Its stream codec owns
            // the private wire constructor and lets playback rebuild it for a mapped entity ID.
            return ClientboundRotateHeadPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    record Value(int entityId, byte headYaw) {
        // Keep the quantized angle byte verbatim; converting it to degrees and back is lossy.
    }
}
