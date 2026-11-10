package dev.voldechse.replayframework.prototype.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Objects;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/** Native Paper 26.3 PLAY codec bound to one verified connection protocol. */
public final class Paper26_3ConnectionCodecSession implements PacketCodecSession {

    private final ProtocolInfo<?> protocolInfo;

    public Paper26_3ConnectionCodecSession(ProtocolInfo<?> protocolInfo) {
        this.protocolInfo = Objects.requireNonNull(protocolInfo, "protocolInfo");
        if (protocolInfo.id() != ConnectionProtocol.PLAY
                || protocolInfo.flow() != PacketFlow.CLIENTBOUND
                || protocolInfo.codec() == null) {
            throw new IllegalArgumentException("codec must be PLAY clientbound");
        }
    }

    @Override
    public Object decode(RawPacketFrame frame) {
        Objects.requireNonNull(frame, "frame");
        ByteBuf buffer = Unpooled.buffer();
        try {
            VarInts.write(buffer, frame.packetId());
            buffer.writeBytes(frame.payload());
            Object decoded = decodeNative(protocolInfo, buffer);
            if (buffer.isReadable()) {
                throw new IllegalArgumentException("packet payload has trailing bytes");
            }
            if (!(decoded instanceof Packet<?> packet)
                    || packet.type() == null
                    || packet.type().flow() != PacketFlow.CLIENTBOUND) {
                throw new IllegalStateException("codec returned a non-clientbound packet");
            }
            return packet;
        } finally {
            buffer.release();
        }
    }

    @Override
    public EncodedPacket encode(Object value) {
        if (!(value instanceof Packet<?> packet)
                || packet.type() == null
                || packet.type().flow() != PacketFlow.CLIENTBOUND) {
            throw new IllegalArgumentException("value must be a clientbound native packet");
        }
        ByteBuf buffer = Unpooled.buffer();
        try {
            encodeNative(protocolInfo, buffer, packet);
            int packetId = VarInts.read(buffer);
            byte[] payload = new byte[buffer.readableBytes()];
            buffer.readBytes(payload);
            return new EncodedPacket(packetId, payload);
        } finally {
            buffer.release();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object decodeNative(ProtocolInfo<?> protocolInfo, ByteBuf buffer) {
        return ((StreamCodec) protocolInfo.codec()).decode(buffer);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encodeNative(
            ProtocolInfo<?> protocolInfo,
            ByteBuf buffer,
            Packet<?> packet) {
        ((StreamCodec) protocolInfo.codec()).encode(buffer, packet);
    }
}
