package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id045LevelChunkWithLightPacketDefinitionTest {

    private static final int PACKET_ID = 45;
    private static final String TYPE_NAME = "play.clientbound.minecraft:level_chunk_with_light";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final int MAX_CHUNK_DATA_BYTES = 2_097_152;
    private static final int MAX_LIGHT_ARRAY_BYTES = 2048;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesChunkTerrainAndLightAsSafeCheckpointState() {
        var definition = new Id045LevelChunkWithLightPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x2D, Id045LevelChunkWithLightPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:level_chunk_with_light",
                Id045LevelChunkWithLightPacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInWorldDomainCatalogRegistryAndCheckpointFamily() {
        var domainMatches = WorldPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(
                Id045LevelChunkWithLightPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(
                Id045LevelChunkWithLightPacketDefinition.class, catalogMatches.getFirst());

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
    }

    @Test
    void passesTheVerifiedNativeChunkPacketThroughPlaybackUnchanged() {
        var definition = new Id045LevelChunkWithLightPacketDefinition();
        ClientboundLevelChunkWithLightPacket nativePacket =
                decode(protocol(), encodeFixture(completeFixture()));
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = codec(nativePacket, decodes, encodes);

        PacketRewriteResult rewritten = definition.handlePlayback(
                frame(new byte[] {1, 2, 3}), descriptor(), codec, rewriter());
        ClientboundLevelChunkWithLightPacket sent = assertInstanceOf(
                ClientboundLevelChunkWithLightPacket.class, rewritten.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, rewritten.decision());
        assertSame(nativePacket, sent);
        assertEquals(1, decodes.get());
        assertEquals(0, encodes.get());
        assertEquals(PACKET_ID, definition.encode(sent, descriptor(), codec).packetId());
        assertEquals(1, encodes.get());

        assertThrows(IncompatibleAdapterException.class, () ->
                definition.rewrite(new Object(), descriptor(), rewriter()));
    }

    @Test
    void nativeProtocolRoundTripsChunkCoordinatesSectionsHeightmapsBlockEntitiesAndLight() {
        ProtocolInfo<?> protocol = protocol();
        Fixture fixture = completeFixture();
        byte[] originalWire = encodeFixture(fixture);

        ByteBuf header = Unpooled.wrappedBuffer(originalWire);
        try {
            assertEquals(PACKET_ID, readVarInt(header));
            assertEquals(fixture.x(), header.readInt());
            assertEquals(fixture.z(), header.readInt());
        } finally {
            header.release();
        }

        ClientboundLevelChunkWithLightPacket decoded = decode(protocol, originalWire);
        assertFixtureEquals(fixture, decoded);

        ByteBuf reencoded = encodePacket(protocol, decoded);
        try {
            assertArrayEquals(originalWire, bytes(reencoded));
        } finally {
            reencoded.release();
        }
    }

    @Test
    void nativeProtocolAcceptsEmptyDataAndSignedChunkCoordinateBoundaries() {
        ProtocolInfo<?> protocol = protocol();

        assertFixtureRoundTrip(protocol, emptyFixture(Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertFixtureRoundTrip(protocol, emptyFixture(Integer.MAX_VALUE, Integer.MIN_VALUE));
    }

    @Test
    void acceptsTheMaximumChunkPayloadAndRejectsLargerChunkOrLightArrays() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf maximumChunk = chunkPacketWithRawSectionData(MAX_CHUNK_DATA_BYTES);
        try {
            ClientboundLevelChunkWithLightPacket decoded = decode(protocol, bytes(maximumChunk));
            FriendlyByteBuf sectionData = decoded.getChunkData().getReadBuffer();
            try {
                assertEquals(MAX_CHUNK_DATA_BYTES, sectionData.readableBytes());
            } finally {
                sectionData.release();
            }
        } finally {
            maximumChunk.release();
        }

        ByteBuf oversizedChunk = chunkPacketHeader(MAX_CHUNK_DATA_BYTES + 1);
        try {
            assertMalformed(protocol, bytes(oversizedChunk));
        } finally {
            oversizedChunk.release();
        }

        assertMalformed(protocol, emptyChunkWithOversizedSkyArray(MAX_LIGHT_ARRAY_BYTES + 1));
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedEmptyPacketPrefix() {
        ProtocolInfo<?> protocol = protocol();
        byte[] complete = encodeFixture(emptyFixture(2, -3));

        for (int length = 0; length < complete.length; length++) {
            assertMalformed(protocol, Arrays.copyOf(complete, length));
        }
    }

    private static Fixture completeFixture() {
        Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        int index = 0;
        for (Heightmap.Types type : Heightmap.Types.values()) {
            long[] values = new long[37];
            values[0] = Long.MIN_VALUE + index;
            values[1] = Long.MAX_VALUE - index;
            values[36] = 0x1020304050607080L + index;
            heightmaps.put(type, values);
            index++;
        }

        byte[] sectionData = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, (byte) 0xFF
        };
        CompoundTag chestTag = new CompoundTag();
        chestTag.putString("CustomName", "Chunk fixture");
        chestTag.putInt("fixture", 26_2);
        CompoundTag signTag = new CompoundTag();
        signTag.putString("front_text", "preserve nested update tag");
        List<BlockEntityFixture> blockEntities = List.of(
                new BlockEntityFixture(12, 15, -64, BlockEntityTypes.CHEST, chestTag),
                new BlockEntityFixture(2, 4, 319, BlockEntityTypes.SIGN, signTag),
                new BlockEntityFixture(7, 8, 12, BlockEntityTypes.BEACON, null));

        BitSet skyMask = bitSet(0, 5, 25);
        BitSet blockMask = bitSet(3, 16);
        BitSet emptySkyMask = bitSet(2, 8);
        BitSet emptyBlockMask = bitSet(4, 17);
        List<byte[]> skyUpdates = List.of(lightBytes(0x15), lightBytes(0xA5), lightBytes(0x3C));
        List<byte[]> blockUpdates = List.of(lightBytes(0x5A), lightBytes(0xC3));

        return new Fixture(
                -3,
                7,
                heightmaps,
                sectionData,
                blockEntities,
                skyMask,
                blockMask,
                emptySkyMask,
                emptyBlockMask,
                skyUpdates,
                blockUpdates);
    }

    private static Fixture emptyFixture(int x, int z) {
        return new Fixture(
                x,
                z,
                new EnumMap<>(Heightmap.Types.class),
                new byte[0],
                List.of(),
                new BitSet(),
                new BitSet(),
                new BitSet(),
                new BitSet(),
                List.of(),
                List.of());
    }

    private static void assertFixtureRoundTrip(ProtocolInfo<?> protocol, Fixture fixture) {
        ClientboundLevelChunkWithLightPacket decoded = decode(protocol, encodeFixture(fixture));
        assertFixtureEquals(fixture, decoded);
    }

    private static void assertFixtureEquals(
            Fixture expected,
            ClientboundLevelChunkWithLightPacket actual) {
        assertEquals(expected.x(), actual.getX());
        assertEquals(expected.z(), actual.getZ());

        Map<Heightmap.Types, long[]> actualHeightmaps = actual.getChunkData().getHeightmaps();
        assertEquals(expected.heightmaps().keySet(), actualHeightmaps.keySet());
        expected.heightmaps().forEach((type, values) ->
                assertArrayEquals(values, actualHeightmaps.get(type)));

        FriendlyByteBuf sectionData = actual.getChunkData().getReadBuffer();
        try {
            byte[] actualSections = new byte[sectionData.readableBytes()];
            sectionData.readBytes(actualSections);
            assertArrayEquals(expected.sectionData(), actualSections);
        } finally {
            sectionData.release();
        }

        List<BlockEntitySnapshot> actualBlockEntities = new ArrayList<>();
        actual.getChunkData().getBlockEntitiesTagsConsumer(actual.getX(), actual.getZ())
                .accept((position, type, tag) -> actualBlockEntities.add(new BlockEntitySnapshot(
                        position.immutable(), type, tag == null ? null : tag.copy())));
        assertEquals(expected.blockEntities().size(), actualBlockEntities.size());
        for (int index = 0; index < expected.blockEntities().size(); index++) {
            BlockEntityFixture expectedEntity = expected.blockEntities().get(index);
            BlockEntitySnapshot actualEntity = actualBlockEntities.get(index);
            assertEquals(
                    new BlockPos(
                            expected.x() * 16 + expectedEntity.localX(),
                            expectedEntity.y(),
                            expected.z() * 16 + expectedEntity.localZ()),
                    actualEntity.position());
            assertSame(expectedEntity.type(), actualEntity.type());
            if (expectedEntity.tag() == null) {
                assertNull(actualEntity.tag());
            } else {
                assertEquals(expectedEntity.tag(), actualEntity.tag());
            }
        }

        var light = actual.getLightData();
        assertEquals(expected.skyMask(), light.getSkyYMask());
        assertEquals(expected.blockMask(), light.getBlockYMask());
        assertEquals(expected.emptySkyMask(), light.getEmptySkyYMask());
        assertEquals(expected.emptyBlockMask(), light.getEmptyBlockYMask());
        assertByteArrays(expected.skyUpdates(), light.getSkyUpdates());
        assertByteArrays(expected.blockUpdates(), light.getBlockUpdates());
    }

    private static void assertByteArrays(List<byte[]> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index), actual.get(index));
        }
    }

    private static byte[] encodeFixture(Fixture fixture) {
        RegistryFriendlyByteBuf output = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), registryAccess());
        try {
            output.writeVarInt(PACKET_ID);
            output.writeInt(fixture.x());
            output.writeInt(fixture.z());
            writeHeightmaps(output, fixture.heightmaps());
            output.writeVarInt(fixture.sectionData().length);
            output.writeBytes(fixture.sectionData());
            writeBlockEntities(output, fixture.blockEntities());
            output.writeBitSet(fixture.skyMask());
            output.writeBitSet(fixture.blockMask());
            output.writeBitSet(fixture.emptySkyMask());
            output.writeBitSet(fixture.emptyBlockMask());
            writeByteArrays(output, fixture.skyUpdates());
            writeByteArrays(output, fixture.blockUpdates());
            return bytes(output);
        } finally {
            output.release();
        }
    }

    private static void writeHeightmaps(
            RegistryFriendlyByteBuf output,
            Map<Heightmap.Types, long[]> heightmaps) {
        output.writeVarInt(heightmaps.size());
        heightmaps.forEach((type, values) -> {
            Heightmap.Types.STREAM_CODEC.encode(output, type);
            output.writeLongArray(values);
        });
    }

    private static void writeBlockEntities(
            RegistryFriendlyByteBuf output,
            List<BlockEntityFixture> blockEntities) {
        output.writeVarInt(blockEntities.size());
        for (BlockEntityFixture blockEntity : blockEntities) {
            output.writeByte(blockEntity.localX() << 4 | blockEntity.localZ());
            output.writeShort(blockEntity.y());
            output.writeVarInt(BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(blockEntity.type()));
            output.writeNbt(blockEntity.tag());
        }
    }

    private static void writeByteArrays(RegistryFriendlyByteBuf output, List<byte[]> values) {
        output.writeVarInt(values.size());
        for (byte[] value : values) {
            output.writeByteArray(value);
        }
    }

    private static ByteBuf chunkPacketWithRawSectionData(int size) {
        RegistryFriendlyByteBuf output = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), registryAccess());
        output.writeVarInt(PACKET_ID);
        output.writeInt(0);
        output.writeInt(0);
        output.writeVarInt(0);
        output.writeVarInt(size);
        output.writeZero(size);
        output.writeVarInt(0);
        writeEmptyLightData(output);
        return output;
    }

    private static ByteBuf chunkPacketHeader(int sectionDataSize) {
        RegistryFriendlyByteBuf output = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), registryAccess());
        output.writeVarInt(PACKET_ID);
        output.writeInt(0);
        output.writeInt(0);
        output.writeVarInt(0);
        output.writeVarInt(sectionDataSize);
        return output;
    }

    private static byte[] emptyChunkWithOversizedSkyArray(int size) {
        RegistryFriendlyByteBuf output = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), registryAccess());
        try {
            output.writeVarInt(PACKET_ID);
            output.writeInt(0);
            output.writeInt(0);
            output.writeVarInt(0);
            output.writeVarInt(0);
            output.writeVarInt(0);
            writeEmptyBitSet(output);
            writeEmptyBitSet(output);
            writeEmptyBitSet(output);
            writeEmptyBitSet(output);
            output.writeVarInt(1);
            output.writeVarInt(size);
            return bytes(output);
        } finally {
            output.release();
        }
    }

    private static void writeEmptyLightData(RegistryFriendlyByteBuf output) {
        writeEmptyBitSet(output);
        writeEmptyBitSet(output);
        writeEmptyBitSet(output);
        writeEmptyBitSet(output);
        output.writeVarInt(0);
        output.writeVarInt(0);
    }

    private static void writeEmptyBitSet(RegistryFriendlyByteBuf output) {
        output.writeLongArray(new long[0]);
    }

    private static int readVarInt(ByteBuf input) {
        int result = 0;
        int shift = 0;
        while (true) {
            if (shift >= 35) {
                throw new IllegalArgumentException("VarInt is too big");
            }
            byte current = input.readByte();
            result |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
    }

    private static BitSet bitSet(int... indices) {
        BitSet result = new BitSet();
        for (int index : indices) {
            result.set(index);
        }
        return result;
    }

    private static byte[] lightBytes(int seed) {
        byte[] values = new byte[MAX_LIGHT_ARRAY_BYTES];
        for (int index = 0; index < values.length; index++) {
            values[index] = (byte) (seed + index * 31);
        }
        return values;
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] wire) {
        ByteBuf input = Unpooled.wrappedBuffer(wire);
        try {
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess()));
    }

    private static RegistryAccess registryAccess() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] result = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), result);
        return result;
    }

    private static ClientboundLevelChunkWithLightPacket decode(
            ProtocolInfo<?> protocol,
            byte[] wire) {
        ByteBuf input = Unpooled.wrappedBuffer(wire);
        try {
            return assertInstanceOf(ClientboundLevelChunkWithLightPacket.class, decode(protocol, input));
        } finally {
            input.release();
        }
    }

    private static ByteBuf encodePacket(
            ProtocolInfo<?> protocol,
            ClientboundLevelChunkWithLightPacket packet) {
        ByteBuf output = Unpooled.buffer();
        try {
            encode(protocol, output, packet);
            return output;
        } catch (RuntimeException | Error failure) {
            output.release();
            throw failure;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf output, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(output, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf input) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(input);
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

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketCodec<Object> codec(
            ClientboundLevelChunkWithLightPacket decoded,
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundLevelChunkWithLightPacket.class, packet);
                encodes.incrementAndGet();
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        PACKET_ID,
                        new byte[0]);
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                return decoded;
            }
        };
    }

    private record Fixture(
            int x,
            int z,
            Map<Heightmap.Types, long[]> heightmaps,
            byte[] sectionData,
            List<BlockEntityFixture> blockEntities,
            BitSet skyMask,
            BitSet blockMask,
            BitSet emptySkyMask,
            BitSet emptyBlockMask,
            List<byte[]> skyUpdates,
            List<byte[]> blockUpdates) {}

    private record BlockEntityFixture(
            int localX,
            int localZ,
            int y,
            BlockEntityType<?> type,
            CompoundTag tag) {}

    private record BlockEntitySnapshot(BlockPos position, BlockEntityType<?> type, CompoundTag tag) {}
}
