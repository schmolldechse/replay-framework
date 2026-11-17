package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.effect;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Objects;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;

/**
 * Identity-safe codec boundary for {@code minecraft:update_mob_effect}.
 *
 * <p>The public packet constructor rebuilds flags from four booleans and gets its
 * amplifier from {@code MobEffectInstance}, whose constructor clamps the value.
 * Replacing only the leading entity-ID VarInt preserves the complete 26.2 wire
 * payload, including raw amplifier values and reserved flag bits; the native
 * connection codec still validates both decoded packet instances.</p>
 */
final class Id132UpdateMobEffectPacketDefinition
        extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x84;
    static final String IDENTIFIER = "minecraft:update_mob_effect";

    Id132UpdateMobEffectPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES));
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.RUNNING_EFFECT);
    }

    @Override
    protected Object decode(RawPacketFrame frame, PacketCodec<Object> codec) {
        Object decoded = super.decode(frame, codec);
        if (!(decoded instanceof ClientboundUpdateMobEffectPacket packet)) {
            throw new IncompatibleAdapterException(
                    "decoded type for " + typeName() + " was " + decoded.getClass().getName());
        }

        byte[] payload = Objects.requireNonNull(frame.payload(), "frame payload");
        int payloadEntityId = readEntityId(payload);
        if (payloadEntityId != packet.getEntityId()) {
            throw new IncompatibleAdapterException(
                    "decoded entity ID differs from the update_mob_effect payload");
        }
        return new DecodedPayload(packet.getEntityId(), payload);
    }

    @Override
    protected PacketRewriteResult rewriteIdentities(
            Object decodedPacket,
            Paper26_2ReplayPacketRewriter context) {
        if (!(decodedPacket instanceof DecodedPayload decoded)) {
            throw new IncompatibleAdapterException(
                    "decoded value for " + typeName() + " is not an update_mob_effect payload");
        }

        int playbackEntityId = context.mapEntityId(decoded.entityId());
        return PacketRewriteResult.send(new RebuiltPayload(
                replaceEntityId(decoded.payload(), decoded.entityId(), playbackEntityId)));
    }

    @Override
    protected PacketCodec.EncodedPacket encode(
            Object packet,
            PacketCodec<Object> codec) {
        if (!(packet instanceof RebuiltPayload rebuilt)) {
            return super.encode(packet, codec);
        }

        RawPacketFrame frame = new RawPacketFrame(
                0L,
                0L,
                0,
                PacketPhase.PLAY,
                PACKET_ID,
                rebuilt.payload());
        Object decoded = codec.decode(frame);
        if (!(decoded instanceof ClientboundUpdateMobEffectPacket nativePacket)) {
            throw new IncompatibleAdapterException(
                    "rewritten payload for " + typeName()
                            + " decoded as " + decoded.getClass().getName());
        }
        return super.encode(nativePacket, codec);
    }

    private static int readEntityId(byte[] payload) {
        ByteBuf input = Unpooled.wrappedBuffer(payload);
        try {
            return VarInt.read(input);
        } finally {
            input.release();
        }
    }

    private static byte[] replaceEntityId(byte[] payload, int expectedId, int replacementId) {
        ByteBuf input = Unpooled.wrappedBuffer(payload);
        ByteBuf output = Unpooled.buffer();
        try {
            int payloadEntityId = VarInt.read(input);
            if (payloadEntityId != expectedId) {
                throw new IncompatibleAdapterException(
                        "update_mob_effect entity ID changed between decode and rewrite");
            }

            VarInt.write(output, replacementId);
            output.writeBytes(input, input.readerIndex(), input.readableBytes());
            byte[] rewritten = new byte[output.readableBytes()];
            output.readBytes(rewritten);
            return rewritten;
        } finally {
            output.release();
            input.release();
        }
    }

    private record DecodedPayload(int entityId, byte[] payload) {

        private DecodedPayload {
            payload = Objects.requireNonNull(payload, "payload").clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    private record RebuiltPayload(byte[] payload) {

        private RebuiltPayload {
            payload = Objects.requireNonNull(payload, "payload").clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
    }
}
