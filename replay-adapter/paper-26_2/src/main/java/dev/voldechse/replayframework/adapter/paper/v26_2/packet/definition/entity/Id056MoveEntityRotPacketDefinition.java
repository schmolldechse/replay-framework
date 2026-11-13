package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

/** Complete read/rewrite/rebuild contract for {@code minecraft:move_entity_rot}. */
final class Id056MoveEntityRotPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundMoveEntityPacket.Rot,
        Id056MoveEntityRotPacketDefinition.Value> {

    static final int PACKET_ID = 0x38;
    static final String IDENTIFIER = "minecraft:move_entity_rot";

    Id056MoveEntityRotPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundMoveEntityPacket.Rot.class);
    }

    @Override
    protected Value read(ClientboundMoveEntityPacket.Rot packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundMoveEntityPacket.Rot.STREAM_CODEC.encode(buffer, packet);
            return new Value(
                    buffer.readVarInt(),
                    buffer.readByte(),
                    buffer.readByte(),
                    buffer.readBoolean());
        } finally {
            buffer.release();
        }
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()),
                value.yRot(),
                value.xRot(),
                value.onGround()));
    }

    @Override
    protected ClientboundMoveEntityPacket.Rot rebuild(Value value) {
        return new ClientboundMoveEntityPacket.Rot(
                value.entityId(), value.yRot(), value.xRot(), value.onGround());
    }

    record Value(int entityId, byte yRot, byte xRot, boolean onGround) {
        // Keep the quantized rotation bytes verbatim; converting them to
        // degrees and back can change the encoded angles.
    }
}
