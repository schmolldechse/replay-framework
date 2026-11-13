package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world.Id043InitializeBorderPacketDefinition.Value;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id043InitializeBorderPacketDefinitionTest {

    private static final int PACKET_ID = 43;
    private static final String TYPE_NAME = "play.clientbound.minecraft:initialize_border";
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
    void classifiesWorldBorderAsSafeCheckpointRelevantState() {
        var definition = new Id043InitializeBorderPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x2B, Id043InitializeBorderPacketDefinition.PACKET_ID);
        assertEquals("minecraft:initialize_border", Id043InitializeBorderPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.SAFE,
                definition.classification().playbackScope());
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
        assertInstanceOf(Id043InitializeBorderPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id043InitializeBorderPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, descriptor.playbackScope());
        assertEquals(
                Paper26_2CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));
    }

    @Test
    void snapshotsStaticAndMovingWorldBordersWithoutLosingFields() {
        var definition = new Id043InitializeBorderPacketDefinition();
        WorldBorder staticBorder = new WorldBorder();
        staticBorder.setCenter(-12_345.5, 67_890.25);
        staticBorder.setSize(5_999_997.0);
        staticBorder.setAbsoluteMaxSize(29_999_984);
        staticBorder.setWarningBlocks(7);
        staticBorder.setWarningTime(80);
        assertRoundTripThroughDefinition(definition, new ClientboundInitializeBorderPacket(staticBorder));

        WorldBorder movingBorder = new WorldBorder();
        movingBorder.setCenter(1_234.5, -9_876.25);
        movingBorder.lerpSizeBetween(512.0, 64.0, 12_345L, 900L);
        movingBorder.setAbsoluteMaxSize(Integer.MAX_VALUE);
        movingBorder.setWarningBlocks(Integer.MIN_VALUE);
        movingBorder.setWarningTime(Integer.MAX_VALUE);
        movingBorder.tick();
        ClientboundInitializeBorderPacket movingPacket =
                new ClientboundInitializeBorderPacket(movingBorder);
        assertEquals(movingBorder.getSize(), movingPacket.getOldSize());
        assertEquals(64.0, movingPacket.getNewSize());
        assertEquals(12_344L, movingPacket.getLerpTime());
        assertRoundTripThroughDefinition(definition, movingPacket);
    }

    @Test
    void playbackRebuildsAndEncodesEveryBorderField() {
        var definition = new Id043InitializeBorderPacketDefinition();
        Value expected = new Value(-300.5, 400.25, 256.0, 0.0, 75L, 29_999_984, -5, 120);
        ClientboundInitializeBorderPacket original = definition.rebuild(expected);
        PacketCodec<Object> codec = codec(original);

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec, rewriter());
        ClientboundInitializeBorderPacket rebuilt = assertInstanceOf(
                ClientboundInitializeBorderPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertNotSame(original, rebuilt);
        assertEquals(expected, definition.read(rebuilt));
        assertEquals(PACKET_ID, definition.encode(rebuilt, descriptor(), codec).packetId());
    }

    @Test
    void rejectsNonFiniteOrImpossibleTransitionFieldsButPreservesSignedWarnings() {
        double[] invalidDoubles = {
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        };
        for (double invalid : invalidDoubles) {
            assertThrows(IncompatibleAdapterException.class, () ->
                    new Value(invalid, 0.0, 1.0, 1.0, 0L, 1, 0, 0));
            assertThrows(IncompatibleAdapterException.class, () ->
                    new Value(0.0, invalid, 1.0, 1.0, 0L, 1, 0, 0));
            assertThrows(IncompatibleAdapterException.class, () ->
                    new Value(0.0, 0.0, invalid, 1.0, 0L, 1, 0, 0));
            assertThrows(IncompatibleAdapterException.class, () ->
                    new Value(0.0, 0.0, 1.0, invalid, 0L, 1, 0, 0));
        }
        assertThrows(IncompatibleAdapterException.class, () ->
                new Value(0.0, 0.0, -0.01, 1.0, 0L, 1, 0, 0));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Value(0.0, 0.0, 1.0, -0.01, 0L, 1, 0, 0));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Value(0.0, 0.0, 1.0, 1.0, -1L, 1, 0, 0));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Value(0.0, 0.0, 1.0, 1.0, 0L, 0, 0, 0));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Value(0.0, 0.0, 1.0, 1.0, 0L, -1, 0, 0));

        Value signedWarnings = new Value(
                0.0, 0.0, 0.0, Double.MAX_VALUE, Long.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE, signedWarnings.warningBlocks());
        assertEquals(Integer.MAX_VALUE, signedWarnings.warningTime());
    }

    @Test
    void nativeProtocolPreservesFieldOrderWidthsAndNumericBoundaries() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id043InitializeBorderPacketDefinition();
        Value expected = new Value(
                -Double.MAX_VALUE,
                Double.MAX_VALUE,
                0.0,
                Double.MAX_VALUE,
                Long.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE);
        assertNativeRoundTrip(protocol, definition, expected);

        Value zeroBoundaries = new Value(0.0, -0.0, 0.0, 0.0, 0L, 1, 0, 0);
        assertNativeRoundTrip(protocol, definition, zeroBoundaries);
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPacketPrefix() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundInitializeBorderPacket packet = new Id043InitializeBorderPacketDefinition()
                .rebuild(new Value(12.5, -98.25, 512.0, 256.0, 300L, 29_999_984, 5, 15));
        ByteBuf complete = encodePacket(protocol, packet);
        byte[] bytes;
        try {
            bytes = new byte[complete.readableBytes()];
            complete.getBytes(complete.readerIndex(), bytes);
        } finally {
            complete.release();
        }

        for (int length = 0; length < bytes.length; length++) {
            assertMalformed(protocol, Arrays.copyOf(bytes, length));
        }
    }

    private static void assertRoundTripThroughDefinition(
            Id043InitializeBorderPacketDefinition definition,
            ClientboundInitializeBorderPacket packet) {
        Value expected = definition.read(packet);
        PacketRewriteResult rewritten = definition.rewrite(packet, descriptor(), rewriter());
        ClientboundInitializeBorderPacket rebuilt = assertInstanceOf(
                ClientboundInitializeBorderPacket.class, rewritten.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, rewritten.decision());
        assertNotSame(packet, rebuilt);
        assertEquals(expected, definition.read(rebuilt));
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            Id043InitializeBorderPacketDefinition definition,
            Value expected) {
        ClientboundInitializeBorderPacket packet = definition.rebuild(expected);
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            assertEquals(expected.centerX(), wire.readDouble());
            assertEquals(expected.centerZ(), wire.readDouble());
            assertEquals(expected.oldSize(), wire.readDouble());
            assertEquals(expected.newSize(), wire.readDouble());
            assertEquals(expected.lerpTime(), readVarLong(wire));
            assertEquals(expected.newAbsoluteMaxSize(), readVarInt(wire));
            assertEquals(expected.warningBlocks(), readVarInt(wire));
            assertEquals(expected.warningTime(), readVarInt(wire));
            assertFalse(wire.isReadable());

            ClientboundInitializeBorderPacket decoded = assertInstanceOf(
                    ClientboundInitializeBorderPacket.class, decode(protocol, encoded.duplicate()));
            assertEquals(expected, definition.read(decoded));
        } finally {
            encoded.release();
        }
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] wireBytes) {
        ByteBuf input = Unpooled.wrappedBuffer(wireBytes);
        try {
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }

    private static int readVarInt(ByteBuf buffer) {
        int result = 0;
        int position = 0;
        while (true) {
            if (position >= 35) {
                throw new IllegalArgumentException("VarInt is too big");
            }
            byte current;
            current = buffer.readByte();
            result |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return result;
            }
            position += 7;
        }
    }

    private static long readVarLong(ByteBuf buffer) {
        long result = 0L;
        int position = 0;
        while (true) {
            if (position >= 70) {
                throw new IllegalArgumentException("VarLong is too big");
            }
            byte current = buffer.readByte();
            result |= (long) (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return result;
            }
            position += 7;
        }
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                PACKET_ID,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.SAFE);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketCodec<Object> codec(ClientboundInitializeBorderPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundInitializeBorderPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        PACKET_ID,
                        new byte[0]);
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                return decoded;
            }
        };
    }
}
