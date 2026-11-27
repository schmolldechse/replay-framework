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
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3MovementDeltas;
import java.util.Objects;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.VecDelta;
import java.util.UUID;

/** Complete read/rewrite/rebuild contract for {@code minecraft:move_entity_pos_rot}. */
final class Id055MoveEntityPosRotPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundMoveEntityPacket.PosRot,
        Id055MoveEntityPosRotPacketDefinition.Value> {

    static final int PACKET_ID = 0x37;
    static final String IDENTIFIER = "minecraft:move_entity_pos_rot";

    Id055MoveEntityPosRotPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundMoveEntityPacket.PosRot.class);
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
        Value value = read(ClientboundMoveEntityPacket.PosRot.class.cast(decodedPacket));
        long[] units = Paper26_3MovementDeltas.totalUnits(value.delta());
        state.moveEntity(connectionId, value.entityId(),
                units[0] / Paper26_3MovementDeltas.UNITS_PER_BLOCK,
                units[1] / Paper26_3MovementDeltas.UNITS_PER_BLOCK,
                units[2] / Paper26_3MovementDeltas.UNITS_PER_BLOCK);
        return CaptureContexts.atEntity(base, state, connectionId, value.entityId());
    }

    @Override
    protected Value read(ClientboundMoveEntityPacket.PosRot packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            // Paper 26.3 wire: entity ID, packed ground flag and step count,
            // the delta, then the two rotation bytes. The protected entity ID
            // and rotation bytes are read from the native encoding.
            ClientboundMoveEntityPacket.PosRot.STREAM_CODEC.encode(buffer, packet);
            int entityId = buffer.readVarInt();
            int end = buffer.writerIndex();
            return new Value(
                    entityId,
                    packet.getPositionDelta(),
                    buffer.getByte(end - 2),
                    buffer.getByte(end - 1),
                    packet.isOnGround());
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
                value.delta(),
                value.yRot(),
                value.xRot(),
                value.onGround()));
    }

    @Override
    protected ClientboundMoveEntityPacket.PosRot rebuild(Value value) {
        return new ClientboundMoveEntityPacket.PosRot(
                value.entityId(),
                value.delta(),
                value.yRot(),
                value.xRot(),
                value.onGround());
    }

    record Value(
            int entityId,
            VecDelta delta,
            byte yRot,
            byte xRot,
            boolean onGround) {

        Value {
            Objects.requireNonNull(delta, "delta");
        }

        // Keep signed rotation bytes verbatim rather than unpacking them to
        // degrees and attempting a second, potentially different quantization.
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
    }
}
