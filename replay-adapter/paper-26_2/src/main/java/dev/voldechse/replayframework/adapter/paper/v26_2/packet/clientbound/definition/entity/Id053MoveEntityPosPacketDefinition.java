package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.entity;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.CaptureContexts;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import java.util.UUID;

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
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundMoveEntityPacket.Pos.class);
    }

    /**
     * A relative step changes where the client places the entity, so it is
     * state: a seek replays it, otherwise every later step starts from a
     * wrong position.
     */
    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.ENTITY_METADATA);
    }



    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        CaptureContext base = super.captureContext(
                decodedPacket, descriptor, connectionId, state, serverTick);
        Value value = read(ClientboundMoveEntityPacket.Pos.class.cast(decodedPacket));
        state.moveEntity(connectionId, value.entityId(), value.deltaX() / 4096.0,
                value.deltaY() / 4096.0, value.deltaZ() / 4096.0);
        return CaptureContexts.atEntity(base, state, connectionId, value.entityId());
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

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
    }
}
