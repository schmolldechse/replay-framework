package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.entity;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.CaptureContexts;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import java.util.UUID;

/** Complete read/rewrite/rebuild contract for {@code minecraft:move_entity_rot}. */
final class Id057MoveEntityRotPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundMoveEntityPacket.Rot,
        Id057MoveEntityRotPacketDefinition.Value> {

    static final int PACKET_ID = 0x39;
    static final String IDENTIFIER = "minecraft:move_entity_rot";

    Id057MoveEntityRotPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundMoveEntityPacket.Rot.class);
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
        Value value = read(ClientboundMoveEntityPacket.Rot.class.cast(decodedPacket));
        return CaptureContexts.atEntity(base, state, connectionId, value.entityId());
    }

    @Override
    protected Value read(ClientboundMoveEntityPacket.Rot packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            // Paper 26.3 wire: entity ID, ground flag, then the two rotation
            // bytes (26.2 wrote the ground flag last).
            ClientboundMoveEntityPacket.Rot.STREAM_CODEC.encode(buffer, packet);
            int entityId = buffer.readVarInt();
            boolean onGround = buffer.readBoolean();
            byte yRot = buffer.readByte();
            byte xRot = buffer.readByte();
            return new Value(entityId, yRot, xRot, onGround);
        } finally {
            buffer.release();
        }
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
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

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
    }
}
