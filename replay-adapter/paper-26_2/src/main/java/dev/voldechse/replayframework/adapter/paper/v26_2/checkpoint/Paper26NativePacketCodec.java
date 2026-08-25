package dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26PacketRegistry;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Objects;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;

/**
 * Paper 26.2 clientbound codec independent of a player connection.
 *
 * <p>The bound protocol object is immutable after construction. Encoding is
 * serialized because the protocol codec and registry-backed buffer decorator
 * are version-owned objects whose thread-safety is not part of the Paper
 * contract.</p>
 */
public final class Paper26NativePacketCodec
        implements Paper26CheckpointEncoder.NativePacketCodec, AutoCloseable {

    private final Paper26PacketRegistry registry;
    private final ProtocolInfo<?> protocolInfo;
    private final Object lock = new Object();
    private boolean closed;

    private Paper26NativePacketCodec(
            Paper26PacketRegistry registry,
            ProtocolInfo<?> protocolInfo) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.protocolInfo = Objects.requireNonNull(protocolInfo, "protocolInfo");
        validateProtocol(protocolInfo);
    }

    /**
     * Binds the current server registry access to the Paper 26.2 clientbound
     * protocol. This factory must be called on the Paper main thread.
     *
     * @param registry verified Paper packet registry
     * @return independent native packet codec
     */
    public static Paper26NativePacketCodec create(Paper26PacketRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null) {
                throw incompatible("Paper MinecraftServer is not initialized", null);
            }
            if (!server.isSameThread()) {
                throw incompatible("Paper 26.2 native codec must be created on the server thread", null);
            }
            RegistryAccess registryAccess = Objects.requireNonNull(
                    server.registryAccess(),
                    "Paper server registry access");
            ProtocolInfo<?> protocolInfo = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                    RegistryFriendlyByteBuf.decorator(registryAccess));
            return new Paper26NativePacketCodec(registry, protocolInfo);
        } catch (IncompatibleAdapterException exception) {
            throw exception;
        } catch (RuntimeException | LinkageError exception) {
            throw incompatible("Paper 26.2 clientbound protocol binding failed", exception);
        }
    }

    /** Creates a codec with an already-bound protocol for adapter-local tests. */
    static Paper26NativePacketCodec forTesting(
            Paper26PacketRegistry registry,
            ProtocolInfo<?> protocolInfo) {
        return new Paper26NativePacketCodec(registry, protocolInfo);
    }

    @Override
    public Paper26CheckpointEncoder.EncodedPacket encode(
            Paper26CheckpointEncoder.PacketBlueprint blueprint) {
        Objects.requireNonNull(blueprint, "blueprint");
        synchronized (lock) {
            ensureOpen();
            Packet<?> packet = blueprint.nativePacket();
            if (packet.type() == null || packet.type().flow() != PacketFlow.CLIENTBOUND) {
                throw incompatible("Paper checkpoint packet is not clientbound", null);
            }

            String packetTypeName = "play.clientbound." + packet.type().id();
            if (!packetTypeName.equals(blueprint.descriptorTypeName())) {
                throw incompatible("native checkpoint packet descriptor mismatch", null);
            }

            ByteBuf buffer = Unpooled.buffer();
            try {
                encodeNative(protocolInfo, buffer, packet);
                int packetId = readVarInt(buffer);
                PacketDescriptor descriptor = registry.find(
                                PacketPhase.PLAY,
                                PacketDescriptor.Direction.CLIENTBOUND,
                                packetId)
                        .orElseThrow(() -> incompatible(
                                "native checkpoint packet ID is not registered: " + packetId,
                                null));
                validateDescriptor(blueprint, packetTypeName, descriptor);

                byte[] payload = new byte[buffer.readableBytes()];
                buffer.readBytes(payload);
                return new Paper26CheckpointEncoder.EncodedPacket(
                        descriptor.typeName(),
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        packetId,
                        blueprint.family(),
                        blueprint.worldKey(),
                        blueprint.targetKey(),
                        payload);
            } catch (IncompatibleAdapterException exception) {
                throw exception;
            } catch (RuntimeException | LinkageError exception) {
                throw incompatible("Paper 26.2 native checkpoint encoding failed", exception);
            } finally {
                buffer.release();
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
        }
    }

    private static void validateProtocol(ProtocolInfo<?> protocolInfo) {
        if (protocolInfo.id() != net.minecraft.network.ConnectionProtocol.PLAY
                || protocolInfo.flow() != PacketFlow.CLIENTBOUND
                || protocolInfo.codec() == null) {
            throw incompatible("Paper checkpoint codec is not PLAY clientbound", null);
        }
    }

    private void validateDescriptor(
            Paper26CheckpointEncoder.PacketBlueprint blueprint,
            String packetTypeName,
            PacketDescriptor descriptor) {
        if (!packetTypeName.equals(descriptor.typeName())
                || !blueprint.descriptorTypeName().equals(descriptor.typeName())) {
            throw incompatible("native checkpoint packet ID descriptor mismatch", null);
        }
        if (descriptor.phase() != PacketPhase.PLAY
                || descriptor.direction() != PacketDescriptor.Direction.CLIENTBOUND
                || descriptor.disposition() == PacketDisposition.CONTROL
                || descriptor.disposition() == PacketDisposition.UNSUPPORTED
                || !descriptor.replayable()
                || !descriptor.checkpointRelevant()) {
            throw incompatible("native checkpoint packet is not replayable", null);
        }
        if (Paper26CheckpointEncoder.familyFor(descriptor.typeName()) != blueprint.family()) {
            throw incompatible("native checkpoint packet family mismatch", null);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Paper 26.2 native checkpoint codec is closed");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encodeNative(
            ProtocolInfo<?> protocolInfo,
            ByteBuf buffer,
            Packet<?> packet) {
        ((StreamCodec) protocolInfo.codec()).encode(buffer, packet);
    }

    private static int readVarInt(ByteBuf buffer) {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            if (!buffer.isReadable()) {
                throw incompatible("Paper checkpoint codec emitted no packet ID", null);
            }
            int current = buffer.readUnsignedByte();
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw incompatible("Paper checkpoint packet ID exceeds VarInt bounds", null);
    }

    private static IncompatibleAdapterException incompatible(String message, Throwable cause) {
        return cause == null
                ? new IncompatibleAdapterException(message)
                : new IncompatibleAdapterException(message, cause);
    }
}
