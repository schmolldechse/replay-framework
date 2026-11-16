package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2CaptureContexts;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import java.util.UUID;

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
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.PacketCheckpointFamily.ENTITY_METADATA);
    }


    @Override
    public boolean deduplicateRecipientCopies() {
        return true;
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
        return Paper26_2CaptureContexts.atEntity(
                base, state, connectionId,
                read(ClientboundRotateHeadPacket.class.cast(decodedPacket)).entityId());
    }

    @Override
    public boolean suppressesRecipientState(
            Object decodedPacket, PacketCaptureState state, long serverTick) {
        return state.movementIsAuthoritative(
                read(ClientboundRotateHeadPacket.class.cast(decodedPacket)).entityId());
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

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.PacketDistribution.BROADCAST;
    }
}
