package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id048LightUpdatePacketDefinitionTest {

    private static final int PACKET_ID = 48;
    private static final String TYPE_NAME = "play.clientbound.minecraft:light_update";
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
    void classifiesLightDeltasAsSafeCheckpointRelevantChunkState() {
        var definition = new Id048LightUpdatePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x30, Id048LightUpdatePacketDefinition.PACKET_ID);
        assertEquals("minecraft:light_update", Id048LightUpdatePacketDefinition.IDENTIFIER);
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
    void registersInWorldCatalogRegistryAndChunkLightCheckpointFamily() {
        var domainMatches = WorldPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id048LightUpdatePacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id048LightUpdatePacketDefinition.class, catalogMatches.getFirst());

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
                Paper26_2CheckpointEncoder.CheckpointPacketFamily.CHUNK_LIGHT,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void preservesMixedLightDataAndMasksAcrossWideSectionIndexes() {
        var definition = new Id048LightUpdatePacketDefinition();
        var expected = mixedValue();

        assertRoundTripThroughDefinition(definition, expected);
        assertNativeRoundTrip(protocol(), definition, expected);
    }

    @Test
    void distinguishesExplicitlyEmptySectionsFromUnchangedSections() {
        var definition = new Id048LightUpdatePacketDefinition();
        var explicitlyEmpty = new Id048LightUpdatePacketDefinition.Value(
                12,
                -34,
                bitSet(),
                bitSet(),
                bitSet(0, 65),
                bitSet(4, 130),
                List.of(),
                List.of());
        var unchanged = new Id048LightUpdatePacketDefinition.Value(
                -1,
                1,
                bitSet(),
                bitSet(),
                bitSet(),
                bitSet(),
                List.of(),
                List.of());

        assertRoundTripThroughDefinition(definition, explicitlyEmpty);
        assertRoundTripThroughDefinition(definition, unchanged);
        assertNativeRoundTrip(protocol(), definition, explicitlyEmpty);
        assertNativeRoundTrip(protocol(), definition, unchanged);
    }

    @Test
    void copiesMasksAndLightArraysOnConstructionAndOnAccess() {
        BitSet inputSkyMask = bitSet(0, 64);
        BitSet inputBlockMask = bitSet(7);
        BitSet inputEmptySkyMask = bitSet(2);
        BitSet inputEmptyBlockMask = bitSet(8);
        byte[] inputSkyArray = lightBytes(0x15);
        byte[] inputSkyArray2 = lightBytes(0xA5);
        byte[] inputBlockArray = lightBytes(0x3C);
        var value = new Id048LightUpdatePacketDefinition.Value(
                1,
                2,
                inputSkyMask,
                inputBlockMask,
                inputEmptySkyMask,
                inputEmptyBlockMask,
                List.of(inputSkyArray, inputSkyArray2),
                List.of(inputBlockArray));

        inputSkyMask.clear();
        inputBlockMask.set(20);
        inputEmptySkyMask.clear();
        inputEmptyBlockMask.set(21);
        inputSkyArray[0] = 0;
        inputSkyArray2[1] = 0;
        inputBlockArray[2] = 0;
        assertEquals(bitSet(0, 64), value.skyMask());
        assertEquals(bitSet(7), value.blockMask());
        assertEquals(bitSet(2), value.emptySkyMask());
        assertEquals(bitSet(8), value.emptyBlockMask());
        assertArrayEquals(lightBytes(0x15), value.skyUpdates().getFirst());
        assertArrayEquals(lightBytes(0xA5), value.skyUpdates().get(1));
        assertArrayEquals(lightBytes(0x3C), value.blockUpdates().getFirst());

        BitSet returnedMask = value.skyMask();
        returnedMask.clear();
        byte[] returnedSkyArray = value.skyUpdates().getFirst();
        returnedSkyArray[0] = 0;
        assertEquals(bitSet(0, 64), value.skyMask());
        assertArrayEquals(lightBytes(0x15), value.skyUpdates().getFirst());
        assertThrows(
                UnsupportedOperationException.class,
                () -> value.skyUpdates().add(lightBytes(1)));
    }

    @Test
    void rejectsInconsistentMasksAndMalformedLightArrayLengths() {
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id048LightUpdatePacketDefinition.Value(
                        0, 0, bitSet(0), bitSet(), bitSet(0), bitSet(),
                        List.of(lightBytes(1)), List.of()));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id048LightUpdatePacketDefinition.Value(
                        0, 0, bitSet(0, 1), bitSet(), bitSet(), bitSet(),
                        List.of(lightBytes(1)), List.of()));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id048LightUpdatePacketDefinition.Value(
                        0, 0, bitSet(0), bitSet(), bitSet(), bitSet(),
                        List.of(new byte[0]), List.of()));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id048LightUpdatePacketDefinition.Value(
                        0, 0, bitSet(0), bitSet(), bitSet(), bitSet(),
                        List.of(new byte[2047]), List.of()));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id048LightUpdatePacketDefinition.Value(
                        0, 0, bitSet(0), bitSet(), bitSet(), bitSet(),
                        List.of(new byte[2049]), List.of()));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id048LightUpdatePacketDefinition.Value(
                        0, 0, bitSet(0), bitSet(), bitSet(), bitSet(),
                        Arrays.asList((byte[]) null), List.of()));
    }

    @Test
    void playbackRebuildsAndEncodesAllCoordinatesMasksAndArrays() {
        var definition = new Id048LightUpdatePacketDefinition();
        var expected = mixedValue();
        var original = definition.rebuild(expected);
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = codec(original, decodes, encodes);

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec, rewriter());
        ClientboundLightUpdatePacket rebuilt = assertInstanceOf(
                ClientboundLightUpdatePacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertNotSame(original, rebuilt);
        assertValueEquals(expected, definition.read(rebuilt));
        assertEquals(PACKET_ID, definition.encode(rebuilt, descriptor(), codec).packetId());
        assertEquals(1, decodes.get());
        assertEquals(1, encodes.get());
    }

    @Test
    void nativeProtocolPreservesExactFieldOrderAndWireWidths() {
        assertNativeRoundTrip(
                protocol(), new Id048LightUpdatePacketDefinition(), mixedValue());
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPacketPrefix() {
        ProtocolInfo<?> protocol = protocol();
        byte[] wire = encodePacketBytes(protocol, new Id048LightUpdatePacketDefinition()
                .rebuild(new Id048LightUpdatePacketDefinition.Value(
                        -17,
                        Integer.MAX_VALUE,
                        bitSet(0),
                        bitSet(1),
                        bitSet(2),
                        bitSet(3),
                        List.of(lightBytes(0x35)),
                        List.of(lightBytes(0x79)))));

        for (int length = 0; length < wire.length; length++) {
            assertMalformed(protocol, Arrays.copyOf(wire, length));
        }
    }

    private static Id048LightUpdatePacketDefinition.Value mixedValue() {
        return new Id048LightUpdatePacketDefinition.Value(
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                bitSet(0, 63, 64, 130),
                bitSet(3, 70),
                bitSet(2, 129),
                bitSet(5, 71),
                List.of(lightBytes(0x15), lightBytes(0xA5), lightBytes(0x3C), lightBytes(0x7E)),
                List.of(lightBytes(0x5A), lightBytes(0xC3)));
    }

    private static void assertRoundTripThroughDefinition(
            Id048LightUpdatePacketDefinition definition,
            Id048LightUpdatePacketDefinition.Value expected) {
        var rewritten = definition.rewrite(expected, rewriter()).value().orElseThrow();
        assertValueEquals(expected, rewritten);
        assertValueEquals(expected, definition.read(definition.rebuild(rewritten)));
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            Id048LightUpdatePacketDefinition definition,
            Id048LightUpdatePacketDefinition.Value expected) {
        byte[] expectedWire = encodePacketBytes(protocol, definition.rebuild(expected));
        ByteBuf wire = Unpooled.wrappedBuffer(expectedWire);
        try {
            assertEquals(PACKET_ID, readVarInt(wire));
            assertEquals(expected.chunkX(), readVarInt(wire));
            assertEquals(expected.chunkZ(), readVarInt(wire));
            assertEquals(expected.skyMask(), readBitSet(wire));
            assertEquals(expected.blockMask(), readBitSet(wire));
            assertEquals(expected.emptySkyMask(), readBitSet(wire));
            assertEquals(expected.emptyBlockMask(), readBitSet(wire));
            assertByteArrays(expected.skyUpdates(), readByteArrays(wire));
            assertByteArrays(expected.blockUpdates(), readByteArrays(wire));
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }

        ByteBuf input = Unpooled.wrappedBuffer(expectedWire);
        try {
            ClientboundLightUpdatePacket decoded = assertInstanceOf(
                    ClientboundLightUpdatePacket.class, decode(protocol, input));
            assertValueEquals(expected, definition.read(decoded));
            assertArrayEquals(expectedWire, encodePacketBytes(protocol, definition.rebuild(
                    definition.read(decoded))));
        } finally {
            input.release();
        }
    }

    private static void assertValueEquals(
            Id048LightUpdatePacketDefinition.Value expected,
            Id048LightUpdatePacketDefinition.Value actual) {
        assertEquals(expected.chunkX(), actual.chunkX());
        assertEquals(expected.chunkZ(), actual.chunkZ());
        assertEquals(expected.skyMask(), actual.skyMask());
        assertEquals(expected.blockMask(), actual.blockMask());
        assertEquals(expected.emptySkyMask(), actual.emptySkyMask());
        assertEquals(expected.emptyBlockMask(), actual.emptyBlockMask());
        assertByteArrays(expected.skyUpdates(), actual.skyUpdates());
        assertByteArrays(expected.blockUpdates(), actual.blockUpdates());
    }

    private static void assertByteArrays(List<byte[]> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index), actual.get(index));
        }
    }

    private static BitSet readBitSet(ByteBuf input) {
        int count = readVarInt(input);
        long[] words = new long[count];
        for (int index = 0; index < count; index++) {
            words[index] = input.readLong();
        }
        return BitSet.valueOf(words);
    }

    private static List<byte[]> readByteArrays(ByteBuf input) {
        int count = readVarInt(input);
        ArrayList<byte[]> arrays = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int length = readVarInt(input);
            byte[] bytes = new byte[length];
            input.readBytes(bytes);
            arrays.add(bytes);
        }
        return List.copyOf(arrays);
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] bytes) {
        ByteBuf input = Unpooled.wrappedBuffer(bytes);
        try {
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
        }
    }

    private static byte[] encodePacketBytes(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            byte[] bytes = new byte[encoded.readableBytes()];
            encoded.getBytes(encoded.readerIndex(), bytes);
            return bytes;
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

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
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
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[0]);
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketCodec<Object> codec(
            ClientboundLightUpdatePacket decoded,
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                assertInstanceOf(ClientboundLightUpdatePacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        PACKET_ID,
                        new byte[] {(byte) PACKET_ID});
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                return decoded;
            }
        };
    }

    private static BitSet bitSet(int... indexes) {
        BitSet bits = new BitSet();
        for (int index : indexes) {
            bits.set(index);
        }
        return bits;
    }

    private static byte[] lightBytes(int seed) {
        byte[] values = new byte[2048];
        for (int index = 0; index < values.length; index++) {
            values[index] = (byte) (seed + index * 31);
        }
        return values;
    }

    private static int readVarInt(ByteBuf input) {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            int current = input.readUnsignedByte();
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new AssertionError("VarInt exceeds five bytes");
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
