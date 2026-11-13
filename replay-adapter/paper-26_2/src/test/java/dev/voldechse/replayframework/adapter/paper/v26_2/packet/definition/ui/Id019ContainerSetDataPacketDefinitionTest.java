package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id019ContainerSetDataPacketDefinitionTest {

    private static final int PACKET_ID = 19;
    private static final String IDENTIFIER = "minecraft:container_set_data";
    private static final String TYPE_NAME = "play.clientbound.minecraft:container_set_data";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesAndRegistersOnlyContainerSetData() {
        var definition = new Id019ContainerSetDataPacketDefinition();
        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x13, Id019ContainerSetDataPacketDefinition.PACKET_ID);
        assertEquals(IDENTIFIER, Id019ContainerSetDataPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());

        assertEquals(1, UiPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName())).count());
        assertEquals(1, Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName())).count());
        var registered = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, registered.typeName());
        assertEquals(PacketDisposition.STATEFUL, registered.disposition());
        assertTrue(registered.captureByDefault());
        assertTrue(registered.replayable());
        assertFalse(registered.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, registered.playbackScope());
    }

    @Test
    void dropsRecordedMenuDataBeforeDecodeAndRejectsDescriptorMismatches() {
        PacketDefinition<Paper26_2ReplayPacketRewriter> definition =
                new Id019ContainerSetDataPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : List.of(
                new byte[0],
                new byte[] {0},
                new byte[] {(byte) 0x80},
                new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                        (byte) 0xFF, (byte) 0xFF, 1},
                new byte[] {1, 2, 3, 4, 5, 6, 7, 8})) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());
            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
        }
        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL,
                definition.rewrite(new Object(), descriptor(), rewriter()).decision());

        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]),
                descriptor(TYPE_NAME, PACKET_ID + 1, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                codec,
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]),
                descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.SAFE),
                codec,
                rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec, rewriter()));
    }

    @Test
    void nativeCodecCoversSignedContainerPropertiesAndNarrowingSemantics() {
        ProtocolInfo<?> protocol = protocol();
        for (int[] values : new int[][] {
            {0, 0, 0},
            {128, 1, 200},
            {16_384, Short.MIN_VALUE, Short.MAX_VALUE},
            {Integer.MAX_VALUE, Short.MAX_VALUE, Short.MIN_VALUE},
            {Integer.MIN_VALUE, -7, -300}
        }) {
            ClientboundContainerSetDataPacket decoded = roundTrip(
                    protocol,
                    new ClientboundContainerSetDataPacket(values[0], values[1], values[2]));
            assertEquals(values[0], decoded.getContainerId());
            assertEquals(values[1], decoded.getId());
            assertEquals(values[2], decoded.getValue());
        }

        ClientboundContainerSetDataPacket canonical = decodePacket(
                protocol, new ClientboundContainerSetDataPacket(9, 65_535, 65_536));
        assertEquals(-1, canonical.getId());
        assertEquals(0, canonical.getValue());

        assertMalformed(protocol, new byte[] {1});
        assertMalformed(protocol, new byte[] {1, 0});
        assertMalformed(protocol, new byte[] {1, 0, 0, 0});
        assertLeavesTrailingByte(protocol, new ClientboundContainerSetDataPacket(3, 2, 1));
    }

    private static ClientboundContainerSetDataPacket roundTrip(
            ProtocolInfo<?> protocol, ClientboundContainerSetDataPacket packet) {
        ByteBuf encoded = encode(protocol, packet);
        try {
            byte[] expectedWire = bytes(encoded);
            assertEquals(PACKET_ID, VarInt.read(encoded.duplicate()));
            ClientboundContainerSetDataPacket decoded =
                    (ClientboundContainerSetDataPacket) decode(protocol, encoded);
            assertFalse(encoded.isReadable());
            ByteBuf reencoded = encode(protocol, decoded);
            try {
                assertArrayEquals(expectedWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
            return decoded;
        } finally {
            encoded.release();
        }
    }

    private static ClientboundContainerSetDataPacket decodePacket(
            ProtocolInfo<?> protocol, ClientboundContainerSetDataPacket packet) {
        ByteBuf encoded = encode(protocol, packet);
        try {
            return (ClientboundContainerSetDataPacket) decode(protocol, encoded);
        } finally {
            encoded.release();
        }
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf malformed = Unpooled.buffer();
        try {
            VarInt.write(malformed, PACKET_ID);
            malformed.writeBytes(payload);
            assertThrows(RuntimeException.class, () -> decode(protocol, malformed));
        } finally {
            malformed.release();
        }
    }

    private static void assertLeavesTrailingByte(
            ProtocolInfo<?> protocol, ClientboundContainerSetDataPacket packet) {
        ByteBuf encoded = encode(protocol, packet);
        try {
            encoded.writeByte(0x7A);
            assertInstanceOf(ClientboundContainerSetDataPacket.class, decode(protocol, encoded));
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
    }

    private static ByteBuf encode(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            encodeInto(protocol, buffer, packet);
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.release();
            throw failure;
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encodeInto(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }

    private static PacketDescriptor descriptor() {
        return descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND, true,
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            String typeName, int packetId, PacketPhase phase,
            PacketDescriptor.Direction direction, boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(typeName, phase, direction, packetId,
                PacketDisposition.STATEFUL,
                direction == PacketDescriptor.Direction.CLIENTBOUND,
                replayable, false, "paper-26.2/" + IDENTIFIER, scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID), Set.of(999));
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes, AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local container data must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local container data must not decode");
            }
        };
    }
}
