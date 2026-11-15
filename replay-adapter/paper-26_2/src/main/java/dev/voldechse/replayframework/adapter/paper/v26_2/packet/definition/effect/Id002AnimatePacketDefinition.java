package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2CaptureContexts;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:animate}.
 *
 * <p>Paper 26.2 carries main-hand and off-hand swings, wake-up, critical-hit
 * and magic-critical-hit effects in this packet. The action remains a raw
 * unsigned byte so unknown values are preserved without reinterpretation.</p>
 */
final class Id002AnimatePacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundAnimatePacket,
        Id002AnimatePacketDefinition.Value> {

    static final int PACKET_ID = 0x02;
    static final String IDENTIFIER = "minecraft:animate";

    Id002AnimatePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundAnimatePacket.class);
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
                ClientboundAnimatePacket.class.cast(decodedPacket).getId());
    }

    @Override
    public boolean suppressesRecipientState(
            Object decodedPacket, PacketCaptureState state, long serverTick) {
        Value value = read(ClientboundAnimatePacket.class.cast(decodedPacket));
        return (value.action() == ClientboundAnimatePacket.SWING_MAIN_HAND
                        || value.action() == ClientboundAnimatePacket.SWING_OFF_HAND)
                && state.swingIsAuthoritative(value.entityId(), serverTick);
    }

    @Override
    protected Value read(ClientboundAnimatePacket packet) {
        return new Value(packet.getId(), packet.getAction());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()), value.action()));
    }

    @Override
    protected ClientboundAnimatePacket rebuild(Value value) {
        // The native type exposes no constructor for an already rewritten ID.
        // Reusing its stream codec preserves the exact version-bound wire contract.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(value.entityId());
            buffer.writeByte(value.action());
            return ClientboundAnimatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    record Value(int entityId, int action) {

        Value {
            if (action < 0 || action > 0xFF) {
                throw new IncompatibleAdapterException(
                        "animate action is not an unsigned byte: " + action);
            }
        }
    }
}
