package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

/** Complete read/rewrite/rebuild contract for {@code minecraft:move_entity_pos}. */
final class Id053MoveEntityPosPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundMoveEntityPacket.Pos,
        Id053MoveEntityPosPacketDefinition.Value> {

    static final int PACKET_ID = 0x35;
    static final String IDENTIFIER = "minecraft:move_entity_pos";

    Id053MoveEntityPosPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundMoveEntityPacket.Pos.class);
    }

    @Override
    protected Value read(ClientboundMoveEntityPacket.Pos packet) {
        return new Value(
                readEntityId(packet),
                packet.getXa(),
                packet.getYa(),
                packet.getZa(),
                packet.isOnGround());
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
                value.onGround()));
    }

    @Override
    protected ClientboundMoveEntityPacket.Pos rebuild(Value value) {
        return new ClientboundMoveEntityPacket.Pos(
                value.entityId(),
                value.deltaX(),
                value.deltaY(),
                value.deltaZ(),
                value.onGround());
    }

    private static int readEntityId(ClientboundMoveEntityPacket.Pos packet) {
        // The NMS entity ID field is protected. Read it through the exact
        // version-native codec instead of using reflection or a parallel parser.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundMoveEntityPacket.Pos.STREAM_CODEC.encode(buffer, packet);
            return buffer.readVarInt();
        } finally {
            buffer.release();
        }
    }

    record Value(int entityId, short deltaX, short deltaY, short deltaZ, boolean onGround) {
        // Keep the signed fixed-point shorts verbatim: converting the 1/4096-block
        // deltas to floating point and back can change a movement update.
    }
}
