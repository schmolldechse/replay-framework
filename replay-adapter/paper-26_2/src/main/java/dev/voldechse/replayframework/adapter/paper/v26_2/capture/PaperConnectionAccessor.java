package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.entity.Player;
import org.bukkit.craftbukkit.entity.CraftPlayer;

/**
 * Adapter-only access to Paper connections and the live 26.2 packet codec.
 */
public final class PaperConnectionAccessor {

    private static final String PACKET_ENCODER_NAME = "encoder";
    private static final Field PACKET_ENCODER_PROTOCOL_INFO = protocolInfoField();

    /**
     * Resolves one open Paper player connection for the playback boundary.
     * The returned handle is a snapshot; callers must still handle a channel
     * closing before their event-loop operation runs.
     */
    public ConnectionHandle connectionFor(Player player) {
        Objects.requireNonNull(player, "player");
        return connectionOf(player);
    }

    /**
     * Decodes a raw PLAY clientbound frame with the codec currently bound to
     * the target connection. Transport compression and encryption are not part
     * of a replay payload and are intentionally not involved here.
     */
    public Object decodeReplayFrame(
            ConnectionHandle connection,
            RawPacketFrame frame,
            PacketRegistry registry) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(registry, "registry");
        if (!connection.channel().isOpen()) {
            throw incompatible("Paper playback channel is closed", null);
        }
        if (frame.phase() != PacketPhase.PLAY) {
            throw incompatible("replay frame is not in PLAY phase", null);
        }
        PacketDescriptor descriptor = registry.find(
                        frame.phase(), PacketDescriptor.Direction.CLIENTBOUND, frame.packetId())
                .orElseThrow(() -> incompatible("replay packet ID is not in the verified registry", null));
        if (!registry.replayAllowed(
                frame.phase(), PacketDescriptor.Direction.CLIENTBOUND, frame.packetId())
                || !descriptor.replayable()) {
            throw incompatible("replay packet is blocked by the verified registry", null);
        }
        return decodeClientboundFrame(connection, frame);
    }

    private Object decodeClientboundFrame(ConnectionHandle connection, RawPacketFrame frame) {
        if (!connection.channel().isOpen()) {
            throw incompatible("Paper clientbound channel is closed", null);
        }

        ChannelHandler encoder = connection.channel().pipeline().get(PACKET_ENCODER_NAME);
        if (!(encoder instanceof PacketEncoder<?> packetEncoder)) {
            throw incompatible("Paper packet encoder is not installed", null);
        }

        ProtocolInfo<?> protocolInfo = protocolInfoOf(packetEncoder);
        if (phaseOf(protocolInfo.id()) != frame.phase()
                || protocolInfo.flow() != PacketFlow.CLIENTBOUND) {
            throw incompatible("Paper clientbound codec does not match the captured phase", null);
        }

        ByteBuf buffer = Unpooled.buffer();
        try {
            writeVarInt(buffer, frame.packetId());
            buffer.writeBytes(frame.payload());
            Object decoded = decode(protocolInfo, buffer);
            if (buffer.isReadable()) {
                throw incompatible("replay packet payload has trailing bytes", null);
            }
            if (!(decoded instanceof Packet<?> packet)
                    || packet.type() == null
                    || packet.type().flow() != PacketFlow.CLIENTBOUND) {
                throw incompatible("Paper clientbound codec returned an invalid packet", null);
            }
            return packet;
        } catch (IncompatibleAdapterException exception) {
            throw exception;
        } catch (RuntimeException | LinkageError exception) {
            throw incompatible("Paper 26.2 clientbound codec failed for packet ID " + frame.packetId(), exception);
        } finally {
            buffer.release();
        }
    }

    /**
     * Encodes one outbound Paper packet with the codec currently bound to the
     * channel, then removes the protocol VarInt from the captured body.
     *
     * @param connection target channel
     * @param outboundPacket packet supplied by Paper
     * @param registry verified 26.2 packet registry
     * @return phase, direction, numeric packet ID and uncompressed packet body
     */
    public WirePacket inspectAndEncode(
            ConnectionHandle connection,
            Object outboundPacket,
            PacketRegistry registry) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(outboundPacket, "outboundPacket");
        Objects.requireNonNull(registry, "registry");
        if (!(outboundPacket instanceof Packet<?> packet)) {
            throw incompatible("Paper outbound message is not a Minecraft packet", null);
        }

        ChannelPipeline pipeline = connection.channel().pipeline();
        ChannelHandler encoder = pipeline.get(PACKET_ENCODER_NAME);
        if (!(encoder instanceof PacketEncoder<?>)) {
            throw incompatible("Paper packet encoder is not installed", null);
        }

        ProtocolInfo<?> protocolInfo = protocolInfoOf((PacketEncoder<?>) encoder);
        PacketFlow flow = protocolInfo.flow();
        PacketDescriptor.Direction direction = directionOf(flow);
        PacketPhase phase = phaseOf(protocolInfo.id());

        if (packet.type() == null || packet.type().flow() != flow) {
            throw incompatible("Paper outbound packet direction does not match the live codec", null);
        }

        ByteBuf buffer = Unpooled.buffer();
        try {
            encode(protocolInfo, buffer, packet);
            int packetId = readVarInt(buffer);
            if (phase == PacketPhase.PLAY
                    && registry.find(phase, direction, packetId).isEmpty()) {
                throw incompatible("Paper PLAY outbound packet is not in the verified registry", null);
            }

            byte[] payload = new byte[buffer.readableBytes()];
            buffer.readBytes(payload);
            return new WirePacket(phase, direction, packetId, payload);
        } catch (IncompatibleAdapterException exception) {
            throw exception;
        } catch (RuntimeException | LinkageError exception) {
            throw incompatible("Paper 26.2 packet codec failed", exception);
        } finally {
            buffer.release();
        }
    }

    /** Returns the current Paper server tick used by capture sequencing. */
    public long currentServerTick() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            throw incompatible("Paper server is not available", null);
        }
        int tick = server.getTickCount();
        if (tick < 0) {
            throw incompatible("Paper server tick is negative", null);
        }
        return tick;
    }

    private static ConnectionHandle connectionOf(Player player) {
        if (!(player instanceof CraftPlayer craftPlayer)) {
            return null;
        }
        ServerPlayer serverPlayer = craftPlayer.getHandle();
        if (serverPlayer == null || serverPlayer.connection == null) {
            return null;
        }
        Connection connection = serverPlayer.connection.connection;
        Channel channel = connection == null ? null : connection.channel;
        if (channel == null || !channel.isOpen()) {
            return null;
        }
        return new ConnectionHandle(player.getUniqueId(), channel);
    }

    private static ProtocolInfo<?> protocolInfoOf(PacketEncoder<?> encoder) {
        try {
            return (ProtocolInfo<?>) PACKET_ENCODER_PROTOCOL_INFO.get(encoder);
        } catch (IllegalAccessException exception) {
            throw incompatible("Paper packet encoder codec is inaccessible", exception);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(
            ProtocolInfo<?> protocolInfo,
            ByteBuf buffer,
            Packet<?> packet) {
        ((net.minecraft.network.codec.StreamCodec) protocolInfo.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object decode(ProtocolInfo<?> protocolInfo, ByteBuf buffer) {
        return ((net.minecraft.network.codec.StreamCodec) protocolInfo.codec()).decode(buffer);
    }

    private static PacketDescriptor.Direction directionOf(PacketFlow flow) {
        if (flow == PacketFlow.CLIENTBOUND) {
            return PacketDescriptor.Direction.CLIENTBOUND;
        }
        if (flow == PacketFlow.SERVERBOUND) {
            return PacketDescriptor.Direction.SERVERBOUND;
        }
        throw incompatible("Paper packet flow is unknown", null);
    }

    private static PacketPhase phaseOf(net.minecraft.network.ConnectionProtocol protocol) {
        return switch (protocol) {
            case HANDSHAKING -> PacketPhase.HANDSHAKING;
            case STATUS -> PacketPhase.STATUS;
            case LOGIN -> PacketPhase.LOGIN;
            case CONFIGURATION -> PacketPhase.CONFIGURATION;
            case PLAY -> PacketPhase.PLAY;
        };
    }

    private static int readVarInt(ByteBuf buffer) {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            if (!buffer.isReadable()) {
                throw incompatible("Paper packet codec emitted no packet ID", null);
            }
            int current = buffer.readUnsignedByte();
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw incompatible("Paper packet ID exceeds VarInt bounds", null);
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        if (value < 0) {
            throw incompatible("replay packet ID is negative", null);
        }
        while ((value & ~0x7F) != 0) {
            buffer.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buffer.writeByte(value);
    }

    private static Field protocolInfoField() {
        try {
            Field field = PacketEncoder.class.getDeclaredField("protocolInfo");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw incompatible("Paper packet encoder layout is incompatible", exception);
        }
    }

    private static IncompatibleAdapterException incompatible(String message, Throwable cause) {
        return cause == null
                ? new IncompatibleAdapterException(message)
                : new IncompatibleAdapterException(message, cause);
    }

    /** Immutable connection identity used by the adapter-internal capture path. */
    public record ConnectionHandle(
            UUID connectionId,
            Channel channel) {
        public ConnectionHandle {
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(channel, "channel");
        }
    }

    /** Immutable result of one live-codec inspection. */
    public record WirePacket(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId,
            byte[] payload) {
        public WirePacket {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(direction, "direction");
            if (packetId < 0) {
                throw new IllegalArgumentException("packetId must not be negative");
            }
            Objects.requireNonNull(payload, "payload");
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
