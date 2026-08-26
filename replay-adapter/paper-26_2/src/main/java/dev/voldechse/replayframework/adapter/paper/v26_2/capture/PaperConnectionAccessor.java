package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
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
     * Installs a viewer gate immediately before Paper's packet encoder. All
     * pipeline mutations remain event-loop local and installation failures are
     * routed to the caller instead of escaping into Netty.
     */
    public void installPlaybackGate(
            ConnectionHandle connection,
            String handlerName,
            ChannelOutboundHandler gate,
            Consumer<Throwable> installationFailureHandler) {
        Objects.requireNonNull(connection, "connection");
        requireHandlerName(handlerName);
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(installationFailureHandler, "installationFailureHandler");

        connection.channel().eventLoop().execute(() -> {
            try {
                ChannelPipeline pipeline = connection.channel().pipeline();
                if (pipeline.get(handlerName) != null) {
                    throw incompatible("reserved playback handler name is already in use", null);
                }

                ChannelHandlerContext encoderContext = pipeline.context(PacketEncoder.class);
                if (encoderContext == null
                        || !(encoderContext.handler() instanceof PacketEncoder<?>)) {
                    throw incompatible("Paper packet encoder is not installed", null);
                }
                pipeline.addAfter(encoderContext.name(), handlerName, gate);
            } catch (Throwable failure) {
                reportInstallationFailure(installationFailureHandler, failure);
            }
        });
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

    /**
     * Decodes an observed clientbound frame only to derive its capture scope.
     * The packet still remains non-replayable unless the registry separately
     * grants a playback contract.
     */
    public Object decodeCapturedFrame(
            ConnectionHandle connection,
            RawPacketFrame frame,
            PacketRegistry registry) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(registry, "registry");
        registry.find(frame.phase(), PacketDescriptor.Direction.CLIENTBOUND, frame.packetId())
                .orElseThrow(() -> incompatible(
                        "captured packet ID is not in the verified registry", null));
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

    /** Removes a named handler on the channel event loop; missing handlers are safe. */
    public void uninstallHandler(ConnectionHandle connection, String handlerName) {
        Objects.requireNonNull(connection, "connection");
        requireHandlerName(handlerName);
        try {
            connection.channel().eventLoop().execute(() -> removeHandler(connection, handlerName));
        } catch (Throwable ignored) {
            // A closed event loop is an expected disconnect/shutdown path.
        }
    }

    /**
     * Removes a handler before returning when called off the channel event
     * loop. This is used by playback cleanup so the live player state is not
     * restored while the replay gate can still suppress its packets.
     */
    public void uninstallHandlerAndWait(ConnectionHandle connection, String handlerName) {
        Objects.requireNonNull(connection, "connection");
        requireHandlerName(handlerName);
        if (connection.channel().eventLoop().inEventLoop()) {
            removeHandler(connection, handlerName);
            return;
        }

        java.util.concurrent.CompletableFuture<Void> removed =
                new java.util.concurrent.CompletableFuture<>();
        try {
            connection.channel().eventLoop().execute(() -> {
                try {
                    removeHandler(connection, handlerName);
                    removed.complete(null);
                } catch (Throwable failure) {
                    removed.completeExceptionally(failure);
                }
            });
            removed.join();
        } catch (Throwable ignored) {
            // A closed event loop is an expected disconnect/shutdown path.
        }
    }

    private static void removeHandler(ConnectionHandle connection, String handlerName) {
        try {
            if (connection.channel().pipeline().get(handlerName) != null) {
                connection.channel().pipeline().remove(handlerName);
            }
        } catch (Throwable ignored) {
            // A closed or already dismantled Paper channel is an expected
            // quit path and must not turn shutdown into a second failure.
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

    /**
     * Extracts only semantic facts that Paper 26.2 exposes as typed packet or
     * connection state. This method is deliberately adapter-local: the core
     * receives the resulting immutable context and never reflects over NMS or
     * guesses from serialized payload bytes.
     *
     * <p>Packets carrying multiple positions, relative movement or an opaque
     * state aggregate return no position. Treating a representative point as
     * the whole packet would silently violate a bounded recording region.</p>
     */
    public CaptureContext captureContext(
            ConnectionHandle connection,
            Object outboundPacket,
            PacketDescriptor descriptor) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(outboundPacket, "outboundPacket");
        Objects.requireNonNull(descriptor, "descriptor");

        Optional<String> chat = normalizedChat(outboundPacket);
        Optional<net.kyori.adventure.key.Key> customChannel = customPayloadChannel(outboundPacket);
        Optional<BlockPosition> position = packetPosition(outboundPacket);
        return new CaptureContext(
                Optional.of(descriptor.disposition()),
                connection.world(),
                position,
                chat,
                customChannel);
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
        Optional<net.kyori.adventure.key.Key> world = Optional.ofNullable(player.getWorld().getKey());
        Optional<BlockPosition> position = Optional.ofNullable(player.getLocation())
                .map(location -> new BlockPosition(
                        location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        return new ConnectionHandle(player.getUniqueId(), channel, world, position);
    }

    private static Optional<String> normalizedChat(Object packet) {
        final String text;
        if (packet instanceof ClientboundSystemChatPacket systemChat) {
            text = systemChat.content().getString();
        } else if (packet instanceof ClientboundPlayerChatPacket playerChat) {
            text = playerChat.unsignedContent().getString();
        } else if (packet instanceof ClientboundDisguisedChatPacket disguisedChat) {
            text = disguisedChat.message().getString();
        } else {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    private static Optional<net.kyori.adventure.key.Key> customPayloadChannel(Object packet) {
        if (!(packet instanceof ClientboundCustomPayloadPacket customPayload)) {
            return Optional.empty();
        }
        Identifier identifier = customPayload.payload().type().id();
        return Optional.of(net.kyori.adventure.key.Key.key(identifier.toString()));
    }

    private static Optional<BlockPosition> packetPosition(Object packet) {
        if (packet instanceof ClientboundBlockUpdatePacket blockUpdate) {
            return Optional.of(toApiPosition(blockUpdate.getPos()));
        }
        if (packet instanceof ClientboundBlockEntityDataPacket blockEntityData) {
            return Optional.of(toApiPosition(blockEntityData.getPos()));
        }
        if (packet instanceof ClientboundAddEntityPacket addEntity) {
            return Optional.of(new BlockPosition(
                    floor(addEntity.getX()), floor(addEntity.getY()), floor(addEntity.getZ())));
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport
                && teleport.relatives().isEmpty()) {
            return Optional.of(toApiPosition(BlockPos.containing(teleport.change().position())));
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
            return Optional.of(toApiPosition(BlockPos.containing(sync.values().position())));
        }
        // Chunk and section updates encode multiple positions. Returning an
        // arbitrary origin would make a bounded scope accept or reject the
        // complete update incorrectly, so the safe value is unknown.
        return Optional.empty();
    }

    private static BlockPosition toApiPosition(BlockPos position) {
        return new BlockPosition(position.getX(), position.getY(), position.getZ());
    }

    private static int floor(double value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw incompatible("Paper packet position exceeds block-coordinate bounds", null);
        }
        return (int) Math.floor(value);
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

    private static void requireHandlerName(String handlerName) {
        Objects.requireNonNull(handlerName, "handlerName");
        if (handlerName.isEmpty() || PACKET_ENCODER_NAME.equals(handlerName)) {
            throw new IllegalArgumentException("invalid packet handler name");
        }
    }

    private static void reportInstallationFailure(
            Consumer<Throwable> installationFailureHandler,
            Throwable failure) {
        try {
            installationFailureHandler.accept(failure);
        } catch (Throwable ignored) {
            // A diagnostic callback must never escape the Netty event loop.
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
            Channel channel,
            Optional<net.kyori.adventure.key.Key> world,
            Optional<BlockPosition> position) {
        public ConnectionHandle(UUID connectionId, Channel channel) {
            this(connectionId, channel, Optional.empty(), Optional.empty());
        }

        public ConnectionHandle {
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(channel, "channel");
            world = Objects.requireNonNull(world, "world");
            position = Objects.requireNonNull(position, "position");
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
