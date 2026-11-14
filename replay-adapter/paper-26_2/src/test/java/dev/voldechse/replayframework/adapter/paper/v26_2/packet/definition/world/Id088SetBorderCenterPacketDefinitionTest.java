package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

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
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2CheckpointEncoder;
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
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id088SetBorderCenterPacketDefinitionTest {

    private static final int PACKET_ID = 88;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_border_center";
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
    void classifiesBorderCenterAsSafeCheckpointRelevantWorldState() {
        var definition = new Id088SetBorderCenterPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x58, Id088SetBorderCenterPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:set_border_center",
                Id088SetBorderCenterPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE,
                definition.classification().playbackScope());
        assertEquals(Paper26_2CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));
    }

    @Test
    void registersExactlyOnceInWorldDomainCatalogAndPaperRegistry() {
        var domainMatches = WorldPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id088SetBorderCenterPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id088SetBorderCenterPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void nativeProtocolPreservesBothDoubleCoordinatesAndTheirExactOrder() {
        ProtocolInfo<?> protocol = protocol();
        List<double[]> centers = List.of(
                new double[] {0.0, 0.0},
                new double[] {123.5, -987.25},
                new double[] {-0.0, 0.0},
                new double[] {Double.MIN_VALUE, -Double.MIN_NORMAL},
                new double[] {30_000_000.0, -30_000_000.0},
                new double[] {Double.MAX_VALUE, -Double.MAX_VALUE});

        for (double[] center : centers) {
            assertNativeRoundTrip(protocol, packet(center[0], center[1]));
        }
    }

    @Test
    void playbackReadsRewritesAndRebuildsTheSameWorldCenter() {
        var definition = new Id088SetBorderCenterPacketDefinition();
        for (double[] center : List.of(
                new double[] {-0.0, 12.75},
                new double[] {-30_000_000.0, 30_000_000.0},
                new double[] {Double.MAX_VALUE, Double.MIN_VALUE})) {
            ClientboundSetBorderCenterPacket original = packet(center[0], center[1]);
            PacketRewriteResult result = definition.handlePlayback(
                    frame(new byte[0]), descriptor(), codec(original), rewriter());
            ClientboundSetBorderCenterPacket rebuilt = assertInstanceOf(
                    ClientboundSetBorderCenterPacket.class, result.packet().orElseThrow());

            assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
            assertRawDoubleEquals(center[0], rebuilt.getNewCenterX());
            assertRawDoubleEquals(center[1], rebuilt.getNewCenterZ());
            var encoded = definition.encode(rebuilt, descriptor(), codec(rebuilt));
            assertEquals(PACKET_ID, encoded.packetId());
            assertArrayEquals(nativePayload(rebuilt), encoded.payload());
        }
    }

    @Test
    void rejectsNonFiniteCenterCoordinates() {
        var definition = new Id088SetBorderCenterPacketDefinition();

        for (double[] center : List.of(
                new double[] {Double.NaN, 0.0},
                new double[] {0.0, Double.NaN},
                new double[] {Double.POSITIVE_INFINITY, 0.0},
                new double[] {0.0, Double.NEGATIVE_INFINITY})) {
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.read(packet(center[0], center[1])));
            assertThrows(IncompatibleAdapterException.class,
                    () -> new Id088SetBorderCenterPacketDefinition.Value(
                            center[0], center[1]));
        }
    }

    @Test
    void rejectsDescriptorMismatchesAndNonReplayableDescriptors() {
        var definition = new Id088SetBorderCenterPacketDefinition();
        ClientboundSetBorderCenterPacket original = packet(1.0, -2.0);

        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame(new byte[0]),
                        descriptor(TYPE_NAME, PACKET_ID + 1, true),
                        codec(original), rewriter()));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame(new byte[0]),
                        descriptor("play.clientbound.minecraft:other", PACKET_ID, true),
                        codec(original), rewriter()));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame(new byte[0]),
                        descriptor(TYPE_NAME, PACKET_ID, false),
                        codec(original), rewriter()));
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPrefixAndConsumesOnlyItsTwoDoubles() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol, packet(3.5, -8.25));
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            byte[] body = bytes(wire);
            assertEquals(Double.BYTES * 2, body.length);
            for (int length = 0; length < body.length; length++) {
                int prefixLength = length;
                assertNativeDecodeFails(protocol,
                        buffer -> buffer.writeBytes(body, 0, prefixLength));
            }

            encoded.writeByte(0x7A);
            ClientboundSetBorderCenterPacket decoded = assertInstanceOf(
                    ClientboundSetBorderCenterPacket.class, decode(protocol, encoded));
            assertEquals(3.5, decoded.getNewCenterX());
            assertEquals(-8.25, decoded.getNewCenterZ());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundSetBorderCenterPacket original) {
        ByteBuf encoded = encodePacket(protocol, original);
        try {
            byte[] originalWire = bytes(encoded);
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertRawDoubleEquals(original.getNewCenterX(), wire.readDouble());
            assertRawDoubleEquals(original.getNewCenterZ(), wire.readDouble());
            assertFalse(wire.isReadable());

            ClientboundSetBorderCenterPacket decoded = assertInstanceOf(
                    ClientboundSetBorderCenterPacket.class, decode(protocol, encoded));
            assertRawDoubleEquals(original.getNewCenterX(), decoded.getNewCenterX());
            assertRawDoubleEquals(original.getNewCenterZ(), decoded.getNewCenterZ());
            var definition = new Id088SetBorderCenterPacketDefinition();
            assertEquals(
                    new Id088SetBorderCenterPacketDefinition.Value(
                            original.getNewCenterX(), original.getNewCenterZ()),
                    definition.read(decoded));
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encodePacket(protocol, definition.rebuild(definition.read(decoded)));
            try {
                assertArrayEquals(originalWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static ClientboundSetBorderCenterPacket packet(double centerX, double centerZ) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeDouble(centerX);
            buffer.writeDouble(centerZ);
            return ClientboundSetBorderCenterPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static byte[] nativePayload(ClientboundSetBorderCenterPacket packet) {
        ByteBuf encoded = encodePacket(protocol(), packet);
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded));
            return bytes(encoded);
        } finally {
            encoded.release();
        }
    }

    private static ByteBuf encodePacket(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            encode(protocol, buffer, packet);
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

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(TYPE_NAME, PACKET_ID, true);
    }

    private static PacketDescriptor descriptor(String typeName, int packetId, boolean replayable) {
        return new PacketDescriptor(
                typeName,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                replayable,
                true,
                "paper-26.2/" + typeName,
                PacketDescriptor.PlaybackScope.SAFE);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> codec(ClientboundSetBorderCenterPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                ClientboundSetBorderCenterPacket nativePacket = assertInstanceOf(
                        ClientboundSetBorderCenterPacket.class, packet);
                ByteBuf encoded = encodePacket(protocol(), nativePacket);
                try {
                    assertEquals(PACKET_ID, VarInt.read(encoded));
                    return new EncodedPacket(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            PACKET_ID,
                            bytes(encoded));
                } finally {
                    encoded.release();
                }
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                return decoded;
            }
        };
    }

    private static void assertNativeDecodeFails(
            ProtocolInfo<?> protocol,
            java.util.function.Consumer<ByteBuf> bodyWriter) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            VarInt.write(encoded, PACKET_ID);
            bodyWriter.accept(encoded);
            assertThrows(RuntimeException.class, () -> decode(protocol, encoded));
        } finally {
            encoded.release();
        }
    }

    private static void assertRawDoubleEquals(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }
}
