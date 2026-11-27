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

/** Complete read/rewrite/rebuild contract for {@code minecraft:move_entity_pos}. */
final class Id054MoveEntityPosPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundMoveEntityPacket.Pos,
        Id054MoveEntityPosPacketDefinition.Value> {

    static final int PACKET_ID = 0x36;
    static final String IDENTIFIER = "minecraft:move_entity_pos";

    Id054MoveEntityPosPacketDefinition() {
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
        long[] units = Paper26_3MovementDeltas.totalUnits(value.delta());
        state.moveEntity(connectionId, value.entityId(),
                units[0] / Paper26_3MovementDeltas.UNITS_PER_BLOCK,
                units[1] / Paper26_3MovementDeltas.UNITS_PER_BLOCK,
                units[2] / Paper26_3MovementDeltas.UNITS_PER_BLOCK);
        return CaptureContexts.atEntity(base, state, connectionId, value.entityId());
    }

    @Override
    protected Value read(ClientboundMoveEntityPacket.Pos packet) {
        return new Value(
                readEntityId(packet),
                packet.getPositionDelta(),
                packet.isOnGround());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()),
                value.delta(),
                value.onGround()));
    }

    @Override
    protected ClientboundMoveEntityPacket.Pos rebuild(Value value) {
        return new ClientboundMoveEntityPacket.Pos(
                value.entityId(),
                value.delta(),
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

    record Value(int entityId, VecDelta delta, boolean onGround) {
        // Paper 26.3 carries a linear or a stepped delta in 1/4096-block units.
        // The immutable native delta is kept as recorded; converting it to
        // floating point and back can change a movement update.
        Value {
            Objects.requireNonNull(delta, "delta");
        }
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
    }
}
