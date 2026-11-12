package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id013ChunksBiomesPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:chunks_biomes";
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesChunkBiomesAsSafeCheckpointState() {
        var definition = new Id013ChunksBiomesPacketDefinition();

        assertEquals(13, definition.packetId());
        assertEquals(0x0D, Id013ChunksBiomesPacketDefinition.PACKET_ID);
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
    void preservesEmptyOrderedAndDuplicateChunkEntries() {
        var definition = new Id013ChunksBiomesPacketDefinition();
        assertTrue(definition.rebuild(definition.read(packet(List.of())))
                .chunkBiomeData()
                .isEmpty());

        ChunkPos repeated = new ChunkPos(-17, 31);
        List<ClientboundChunksBiomesPacket.ChunkBiomeData> expected = List.of(
                data(repeated, new byte[] {1, 2, 3}),
                data(new ChunkPos(Integer.MIN_VALUE, Integer.MAX_VALUE), allByteValues()),
                data(repeated, new byte[] {9, 8}),
                data(new ChunkPos(Integer.MAX_VALUE, Integer.MIN_VALUE), new byte[0]));

        var value = definition.read(packet(expected));
        var rewritten = definition.rewrite(value, rewriter()).value().orElseThrow();
        ClientboundChunksBiomesPacket rebuilt = definition.rebuild(rewritten);

        assertSame(value, rewritten);
        assertEntriesEqual(expected, rebuilt.chunkBiomeData());
    }

    @Test
    void deeplyIsolatesMutableNativeListsAndBuffers() {
        var definition = new Id013ChunksBiomesPacketDefinition();
        byte[] source = new byte[] {10, 20, 30};
        var nativeEntry = data(new ChunkPos(4, -9), source);
        List<ClientboundChunksBiomesPacket.ChunkBiomeData> nativeEntries =
                new ArrayList<>(List.of(nativeEntry));

        var value = definition.read(packet(nativeEntries));
        source[0] = 99;
        nativeEntry.buffer()[1] = 88;
        nativeEntries.clear();

        assertEquals(1, value.chunks().size());
        assertArrayEquals(new byte[] {10, 20, 30}, value.chunks().getFirst().buffer());
        assertThrows(UnsupportedOperationException.class, () -> value.chunks().clear());

        byte[] exposed = value.chunks().getFirst().buffer();
        exposed[2] = 77;
        assertArrayEquals(new byte[] {10, 20, 30}, value.chunks().getFirst().buffer());

        ClientboundChunksBiomesPacket firstRebuild = definition.rebuild(value);
        byte[] rebuiltBuffer = firstRebuild.chunkBiomeData().getFirst().buffer();
        assertNotSame(source, rebuiltBuffer);
        rebuiltBuffer[0] = 66;

        ClientboundChunksBiomesPacket secondRebuild = definition.rebuild(value);
        assertArrayEquals(
                new byte[] {10, 20, 30},
                secondRebuild.chunkBiomeData().getFirst().buffer());
        assertNotSame(rebuiltBuffer, secondRebuild.chunkBiomeData().getFirst().buffer());
    }

    @Test
    void validatesTheCompleteSemanticValueBoundary() {
        byte[] maximum = new byte[Id013ChunksBiomesPacketDefinition.MAX_BIOME_DATA_SIZE];
        maximum[0] = Byte.MIN_VALUE;
        maximum[maximum.length - 1] = Byte.MAX_VALUE;
        var maximumValue = new Id013ChunksBiomesPacketDefinition.ChunkBiomeValue(
                new ChunkPos(0, 0), maximum);

        assertEquals(maximum.length, maximumValue.buffer().length);
        assertEquals(Byte.MIN_VALUE, maximumValue.buffer()[0]);
        assertEquals(Byte.MAX_VALUE, maximumValue.buffer()[maximum.length - 1]);
        assertThrows(NullPointerException.class, () ->
                new Id013ChunksBiomesPacketDefinition.Value(null));
        assertThrows(NullPointerException.class, () ->
                new Id013ChunksBiomesPacketDefinition.Value(Arrays.asList(
                        (Id013ChunksBiomesPacketDefinition.ChunkBiomeValue) null)));
        assertThrows(NullPointerException.class, () ->
                new Id013ChunksBiomesPacketDefinition.ChunkBiomeValue(null, new byte[0]));
        assertThrows(NullPointerException.class, () ->
                new Id013ChunksBiomesPacketDefinition.ChunkBiomeValue(ChunkPos.ZERO, null));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id013ChunksBiomesPacketDefinition.ChunkBiomeValue(
                        ChunkPos.ZERO,
                        new byte[Id013ChunksBiomesPacketDefinition.MAX_BIOME_DATA_SIZE + 1]));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id013ChunksBiomesPacketDefinition().rewrite(
                        new Object(), descriptor(), rewriter()));
    }

    @Test
    void handlesPlaybackAndValidatedEncode() {
        var definition = new Id013ChunksBiomesPacketDefinition();
        ClientboundChunksBiomesPacket original = packet(List.of(
                data(new ChunkPos(10, -20), new byte[] {0, 127, -1}),
                data(new ChunkPos(-30, 40), new byte[0])));

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), rewriter());
        ClientboundChunksBiomesPacket rebuilt = assertInstanceOf(
                ClientboundChunksBiomesPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEntriesEqual(original.chunkBiomeData(), rebuilt.chunkBiomeData());
        assertNotSame(
                original.chunkBiomeData().getFirst().buffer(),
                rebuilt.chunkBiomeData().getFirst().buffer());
        assertEquals(13, definition.encode(rebuilt, descriptor(), codec(original)).packetId());
    }

    @Test
    void nativeProtocolPreservesAllWireFieldsAndLengthBoundaries() {
        ProtocolInfo<?> protocol = protocol();
        int[] lengths = {0, 1, 127, 128, 16_383, 16_384};
        List<ClientboundChunksBiomesPacket.ChunkBiomeData> chunks = new ArrayList<>();
        for (int index = 0; index < lengths.length; index++) {
            byte[] buffer = new byte[lengths[index]];
            Arrays.fill(buffer, (byte) (index - 3));
            chunks.add(data(new ChunkPos(index - 3, 3 - index), buffer));
        }
        chunks.add(data(new ChunkPos(-7, 11), new byte[] {1}));
        chunks.add(data(new ChunkPos(-7, 11), new byte[] {2}));

        assertNativeRoundTrip(protocol, chunks);
    }

    @Test
    void nativeProtocolPreservesCoordinateExtremesAndMaximumBuffer() {
        byte[] maximum = new byte[Id013ChunksBiomesPacketDefinition.MAX_BIOME_DATA_SIZE];
        maximum[0] = 1;
        maximum[maximum.length - 1] = -1;

        assertNativeRoundTrip(protocol(), List.of(
                data(new ChunkPos(Integer.MIN_VALUE, Integer.MIN_VALUE), maximum),
                data(new ChunkPos(Integer.MAX_VALUE, Integer.MAX_VALUE), new byte[0]),
                data(new ChunkPos(Integer.MIN_VALUE, Integer.MAX_VALUE), allByteValues()),
                data(new ChunkPos(Integer.MAX_VALUE, Integer.MIN_VALUE), new byte[] {42})));
    }

    @Test
    void nativeProtocolRejectsMalformedListsPositionsAndBuffers() {
        ProtocolInfo<?> protocol = protocol();

        assertNativeDecodeFails(protocol, buffer -> {});
        assertNativeDecodeFails(protocol, buffer -> buffer.writeByte(0x80));
        assertNativeDecodeFails(protocol, buffer -> writeVarInt(buffer, -1));
        assertNativeDecodeFails(protocol, buffer -> writeVarInt(buffer, 1));
        assertNativeDecodeFails(protocol, buffer -> {
            writeVarInt(buffer, 1);
            buffer.writeInt(0);
        });
        assertNativeDecodeFails(protocol, buffer -> {
            writeVarInt(buffer, 1);
            buffer.writeLong(ChunkPos.ZERO.pack());
        });
        assertNativeDecodeFails(protocol, buffer -> {
            writeVarInt(buffer, 1);
            buffer.writeLong(ChunkPos.ZERO.pack());
            buffer.writeByte(0x80);
        });
        assertNativeDecodeFails(protocol, buffer -> {
            writeVarInt(buffer, 1);
            buffer.writeLong(ChunkPos.ZERO.pack());
            writeVarInt(buffer, 4);
            buffer.writeMedium(0);
        });
        assertNativeDecodeFails(protocol, buffer -> {
            writeVarInt(buffer, 1);
            buffer.writeLong(ChunkPos.ZERO.pack());
            writeVarInt(buffer, Id013ChunksBiomesPacketDefinition.MAX_BIOME_DATA_SIZE + 1);
        });
        assertNativeDecodeFails(protocol, buffer -> {
            writeVarInt(buffer, 1);
            buffer.writeLong(ChunkPos.ZERO.pack());
            for (int index = 0; index < 6; index++) {
                buffer.writeByte(0x80);
            }
        });
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            List<ClientboundChunksBiomesPacket.ChunkBiomeData> expected) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, packet(expected));

            ByteBuf wireView = encoded.duplicate();
            assertEquals(13, readVarInt(wireView));
            assertEquals(expected.size(), readVarInt(wireView));
            for (var entry : expected) {
                assertEquals(entry.pos().pack(), wireView.readLong());
                assertEquals(entry.buffer().length, readVarInt(wireView));
                byte[] actual = new byte[entry.buffer().length];
                wireView.readBytes(actual);
                assertArrayEquals(entry.buffer(), actual);
            }
            assertFalse(wireView.isReadable());

            ClientboundChunksBiomesPacket decoded = assertInstanceOf(
                    ClientboundChunksBiomesPacket.class, decode(protocol, encoded));
            assertEntriesEqual(expected, decoded.chunkBiomeData());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertNativeDecodeFails(
            ProtocolInfo<?> protocol,
            Consumer<ByteBuf> bodyWriter) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            writeVarInt(encoded, 13);
            bodyWriter.accept(encoded);
            assertThrows(RuntimeException.class, () -> decode(protocol, encoded));
        } finally {
            encoded.release();
        }
    }

    private static void assertEntriesEqual(
            List<ClientboundChunksBiomesPacket.ChunkBiomeData> expected,
            List<ClientboundChunksBiomesPacket.ChunkBiomeData> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index).pos(), actual.get(index).pos());
            assertArrayEquals(expected.get(index).buffer(), actual.get(index).buffer());
        }
    }

    private static byte[] allByteValues() {
        byte[] values = new byte[256];
        for (int index = 0; index < values.length; index++) {
            values[index] = (byte) index;
        }
        return values;
    }

    private static ClientboundChunksBiomesPacket packet(
            List<ClientboundChunksBiomesPacket.ChunkBiomeData> chunks) {
        return new ClientboundChunksBiomesPacket(chunks);
    }

    private static ClientboundChunksBiomesPacket.ChunkBiomeData data(
            ChunkPos position,
            byte[] buffer) {
        return new ClientboundChunksBiomesPacket.ChunkBiomeData(position, buffer);
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static Paper26_3ReplayPacketRewriter rewriter() {
        return new Paper26_3ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                13,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "paper-26.3/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.SAFE);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 13, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundChunksBiomesPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundChunksBiomesPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        13,
                        new byte[] {13});
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                return decoded;
            }
        };
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
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            int current = buffer.readUnsignedByte();
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new AssertionError("VarInt exceeds five bytes");
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            buffer.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer.writeByte(remaining);
    }
}
