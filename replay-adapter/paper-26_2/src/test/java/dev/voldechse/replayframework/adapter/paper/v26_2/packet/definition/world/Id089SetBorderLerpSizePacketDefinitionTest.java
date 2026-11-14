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
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id089SetBorderLerpSizePacketDefinitionTest {

    private static final int PACKET_ID = 89;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_border_lerp_size";
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
    void classifiesBorderInterpolationAsSafeCheckpointRelevantWorldState() {
        var definition = new Id089SetBorderLerpSizePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x59, Id089SetBorderLerpSizePacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:set_border_lerp_size",
                Id089SetBorderLerpSizePacketDefinition.IDENTIFIER);
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
        assertInstanceOf(Id089SetBorderLerpSizePacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id089SetBorderLerpSizePacketDefinition.class, catalogMatches.getFirst());

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
    void nativeProtocolPreservesStationaryAndMovingSizesAcrossVarLongBoundaries() {
        ProtocolInfo<?> protocol = protocol();
        long[] lerpTimes = {0L, 1L, 127L, 128L, 16_383L, 16_384L, Long.MAX_VALUE};

        assertNativeRoundTrip(protocol, packet(0.0, 0.0, 0L));
        assertNativeRoundTrip(protocol, packet(-0.0, 0.0, 0L));
        for (long lerpTime : lerpTimes) {
            double oldSize = 59_999_970.0;
            double newSize = lerpTime == 0L ? oldSize : 1.25;
            assertNativeRoundTrip(protocol, packet(oldSize, newSize, lerpTime));
        }
        assertNativeRoundTrip(protocol, packet(Double.MAX_VALUE, Double.MAX_VALUE, 0L));
        assertNativeRoundTrip(protocol, packet(Double.MIN_VALUE, Double.MIN_NORMAL, 128L));
    }

    @Test
    void nativeProtocolAcceptsOverlongVarLongAndReencodesItsCanonicalForm() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf input = Unpooled.buffer();
        try {
            VarInt.write(input, PACKET_ID);
            input.writeDouble(12.0);
            input.writeDouble(12.0);
            input.writeByte(0x80);
            input.writeByte(0x00);

            ClientboundSetBorderLerpSizePacket decoded = assertInstanceOf(
                    ClientboundSetBorderLerpSizePacket.class, decode(protocol, input));
            assertEquals(12.0, decoded.getOldSize());
            assertEquals(12.0, decoded.getNewSize());
            assertEquals(0L, decoded.getLerpTime());
            assertFalse(input.isReadable());

            ByteBuf canonical = encodePacket(protocol, decoded);
            try {
                FriendlyByteBuf wire = new FriendlyByteBuf(canonical.duplicate());
                assertEquals(PACKET_ID, VarInt.read(wire));
                assertEquals(12.0, wire.readDouble());
                assertEquals(12.0, wire.readDouble());
                assertEquals(0L, wire.readVarLong());
                assertEquals(1, canonical.readableBytes() - 17);
                assertFalse(wire.isReadable());
            } finally {
                canonical.release();
            }
        } finally {
            input.release();
        }
    }

    @Test
    void playbackPreservesEveryFieldAndEncodesTheSameNativePayload() {
        var definition = new Id089SetBorderLerpSizePacketDefinition();
        for (long lerpTime : new long[] {0L, 128L, Long.MAX_VALUE}) {
            double oldSize = 59_999_970.0;
            double newSize = lerpTime == 0L ? oldSize : 1.25;
            ClientboundSetBorderLerpSizePacket original = packet(oldSize, newSize, lerpTime);
            PacketRewriteResult result = definition.handlePlayback(
                    frame(new byte[0]), descriptor(), codec(original), rewriter());
            ClientboundSetBorderLerpSizePacket rebuilt = assertInstanceOf(
                    ClientboundSetBorderLerpSizePacket.class, result.packet().orElseThrow());

            assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
            assertSizeEquals(oldSize, rebuilt.getOldSize());
            assertSizeEquals(newSize, rebuilt.getNewSize());
            assertEquals(lerpTime, rebuilt.getLerpTime());
            var encoded = definition.encode(rebuilt, descriptor(), codec(rebuilt));
            assertEquals(PACKET_ID, encoded.packetId());
            assertArrayEquals(nativePayload(rebuilt), encoded.payload());
        }
    }

    @Test
    void rejectsInvalidSizesDurationsAndZeroLengthTransitions() {
        var definition = new Id089SetBorderLerpSizePacketDefinition();
        assertThrows(IncompatibleAdapterException.class,
                () -> new Id089SetBorderLerpSizePacketDefinition.Value(-1.0, 1.0, 1L));
        assertThrows(IncompatibleAdapterException.class,
                () -> new Id089SetBorderLerpSizePacketDefinition.Value(1.0, -1.0, 1L));
        assertThrows(IncompatibleAdapterException.class,
                () -> new Id089SetBorderLerpSizePacketDefinition.Value(Double.NaN, 1.0, 1L));
        assertThrows(IncompatibleAdapterException.class,
                () -> new Id089SetBorderLerpSizePacketDefinition.Value(
                        1.0, Double.POSITIVE_INFINITY, 1L));
        assertThrows(IncompatibleAdapterException.class,
                () -> new Id089SetBorderLerpSizePacketDefinition.Value(1.0, 1.0, -1L));
        assertThrows(IncompatibleAdapterException.class,
                () -> new Id089SetBorderLerpSizePacketDefinition.Value(1.0, 2.0, 0L));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.read(packet(-1.0, 1.0, 1L)));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.read(packet(1.0, Double.NaN, 1L)));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.read(packet(1.0, 1.0, -1L)));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.read(packet(1.0, 2.0, 0L)));
        assertEquals(new Id089SetBorderLerpSizePacketDefinition.Value(1.0, 1.0, 0L),
                definition.read(packet(1.0, 1.0, 0L)));
    }

    @Test
    void rejectsDescriptorMismatchesAndNonReplayableDescriptors() {
        var definition = new Id089SetBorderLerpSizePacketDefinition();
        ClientboundSetBorderLerpSizePacket original = packet(12.0, 10.0, 128L);

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
    void nativeProtocolRejectsTruncatedBodiesAndMalformedVarLongs() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol,
                packet(59_999_970.0, 1.25, Long.MAX_VALUE));
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            byte[] body = bytes(wire);
            for (int length = 0; length < body.length; length++) {
                int prefixLength = length;
                assertNativeDecodeFails(protocol,
                        buffer -> buffer.writeBytes(body, 0, prefixLength));
            }
        } finally {
            encoded.release();
        }

        assertNativeDecodeFails(protocol, buffer -> {
            buffer.writeDouble(1.0);
            buffer.writeDouble(2.0);
            for (int index = 0; index < 11; index++) {
                buffer.writeByte(0x80);
            }
        });

        ClientboundSetBorderLerpSizePacket negativeDuration = packet(1.0, 1.0, -1L);
        assertThrows(IncompatibleAdapterException.class,
                () -> new Id089SetBorderLerpSizePacketDefinition().read(negativeDuration));
    }

    @Test
    void nativeProtocolLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol, packet(12.0, 8.0, 128L));
        try {
            encoded.writeByte(0x7A);
            ClientboundSetBorderLerpSizePacket decoded = assertInstanceOf(
                    ClientboundSetBorderLerpSizePacket.class, decode(protocol, encoded));
            assertEquals(12.0, decoded.getOldSize());
            assertEquals(8.0, decoded.getNewSize());
            assertEquals(128L, decoded.getLerpTime());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundSetBorderLerpSizePacket original) {
        ByteBuf encoded = encodePacket(protocol, original);
        try {
            byte[] originalWire = bytes(encoded);
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertSizeEquals(original.getOldSize(), wire.readDouble());
            assertSizeEquals(original.getNewSize(), wire.readDouble());
            assertEquals(original.getLerpTime(), wire.readVarLong());
            assertFalse(wire.isReadable());

            ClientboundSetBorderLerpSizePacket decoded = assertInstanceOf(
                    ClientboundSetBorderLerpSizePacket.class, decode(protocol, encoded));
            assertSizeEquals(original.getOldSize(), decoded.getOldSize());
            assertSizeEquals(original.getNewSize(), decoded.getNewSize());
            assertEquals(original.getLerpTime(), decoded.getLerpTime());
            var definition = new Id089SetBorderLerpSizePacketDefinition();
            assertEquals(
                    definition.read(original),
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

    private static ClientboundSetBorderLerpSizePacket packet(
            double oldSize, double newSize, long lerpTime) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeDouble(oldSize);
            buffer.writeDouble(newSize);
            buffer.writeVarLong(lerpTime);
            return ClientboundSetBorderLerpSizePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static byte[] nativePayload(ClientboundSetBorderLerpSizePacket packet) {
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

    private static PacketCodec<Object> codec(ClientboundSetBorderLerpSizePacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                ClientboundSetBorderLerpSizePacket nativePacket = assertInstanceOf(
                        ClientboundSetBorderLerpSizePacket.class, packet);
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

    private static void assertSizeEquals(double expected, double actual) {
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
