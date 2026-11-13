package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

/** Complete read/rewrite/rebuild contract for {@code minecraft:move_entity_pos_rot}. */
final class Id054MoveEntityPosRotPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundMoveEntityPacket.PosRot,
        Id054MoveEntityPosRotPacketDefinition.Value> {

    static final int PACKET_ID = 0x36;
    static final String IDENTIFIER = "minecraft:move_entity_pos_rot";

    Id054MoveEntityPosRotPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundMoveEntityPacket.PosRot.class);
    }

    @Override
    protected Value read(ClientboundMoveEntityPacket.PosRot packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundMoveEntityPacket.PosRot.STREAM_CODEC.encode(buffer, packet);
            return new Value(
                    buffer.readVarInt(),
                    buffer.readShort(),
                    buffer.readShort(),
                    buffer.readShort(),
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
                value.deltaX(),
                value.deltaY(),
                value.deltaZ(),
                value.yRot(),
                value.xRot(),
                value.onGround()));
    }

    @Override
    protected ClientboundMoveEntityPacket.PosRot rebuild(Value value) {
        return new ClientboundMoveEntityPacket.PosRot(
                value.entityId(),
                value.deltaX(),
                value.deltaY(),
                value.deltaZ(),
                value.yRot(),
                value.xRot(),
                value.onGround());
    }

    record Value(
            int entityId,
            short deltaX,
            short deltaY,
            short deltaZ,
            byte yRot,
            byte xRot,
            boolean onGround) {
        // Keep signed rotation bytes verbatim rather than unpacking them to
        // degrees and attempting a second, potentially different quantization.
    }
}
