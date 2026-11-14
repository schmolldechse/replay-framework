package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id082RespawnPacketDefinitionTest {

    private static final int PACKET_ID = 82;
    private static final String TYPE_NAME = "play.clientbound.minecraft:respawn";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static List<ResourceKey<Level>> dimensions;
    private static final GameType[] PREVIOUS_GAME_TYPES = {
        null, GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE, GameType.SPECTATOR
    };
    private static final long[] SEEDS = {
        Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, 0x0123456789ABCDEFL, Long.MAX_VALUE
    };
    private static final int[] WORLD_INTS = {
        Integer.MIN_VALUE, -1, 0, 1, 127, 128, 255, Integer.MAX_VALUE
    };
    private static RegistryAccess.Frozen registryAccess;
    private static List<Holder<DimensionType>> dimensionTypes;
    private static List<Optional<GlobalPos>> deathLocations;
    private static ProtocolInfo<?> protocol;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        dimensions = List.of(
                Level.OVERWORLD,
                Level.NETHER,
                Level.END,
                ResourceKey.create(Registries.DIMENSION, Identifier.parse("replay:custom_dimension")));
        deathLocations = List.of(
                Optional.empty(),
                Optional.of(GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO)),
                Optional.of(GlobalPos.of(
                        Level.NETHER,
                        new BlockPos(-BlockPos.MAX_HORIZONTAL_COORDINATE, -2048, BlockPos.MAX_HORIZONTAL_COORDINATE))),
                Optional.of(GlobalPos.of(
                        dimensions.getLast(),
                        new BlockPos(BlockPos.MAX_HORIZONTAL_COORDINATE, 2047, -BlockPos.MAX_HORIZONTAL_COORDINATE))));

        HolderLookup.Provider vanillaRegistries = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<DimensionType> vanillaDimensionTypes =
                vanillaRegistries.lookupOrThrow(Registries.DIMENSION_TYPE);
        List<ResourceKey<DimensionType>> dimensionTypeKeys = List.of(
                BuiltinDimensionTypes.OVERWORLD,
                BuiltinDimensionTypes.NETHER,
                BuiltinDimensionTypes.END,
                BuiltinDimensionTypes.OVERWORLD_CAVES);
        MappedRegistry<DimensionType> dimensionTypeRegistry = new MappedRegistry<>(
                Registries.DIMENSION_TYPE, Lifecycle.stable());
        List<Holder<DimensionType>> registeredDimensionTypes = new ArrayList<>();
        for (ResourceKey<DimensionType> key : dimensionTypeKeys) {
            Holder<DimensionType> vanillaHolder = vanillaDimensionTypes.getOrThrow(key);
            registeredDimensionTypes.add(dimensionTypeRegistry.register(
                    key, vanillaHolder.value(), RegistrationInfo.BUILT_IN));
        }
        dimensionTypeRegistry.freeze();

        registryAccess = new RegistryAccess.ImmutableRegistryAccess(
                        List.of((Registry<?>) dimensionTypeRegistry))
                .freeze();
        dimensionTypes = List.copyOf(registeredDimensionTypes);
        protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
    }

    @Test
    void classifiesAndRegistersRespawnAsViewerLocalPlayerState() {
        var definition = new Id082RespawnPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x52, Id082RespawnPacketDefinition.PACKET_ID);
        assertEquals("minecraft:respawn", Id082RespawnPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());

        var domainMatches = PlayerPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id082RespawnPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id082RespawnPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
        assertEquals(
                Paper26_2CheckpointEncoder.CheckpointPacketFamily.DIMENSION_SPAWN,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));
    }

    @Test
    void dropsValidAndMalformedFramesBeforeAnyPlaybackCodecAccess() {
        var definition = new Id082RespawnPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<byte[]> payloads = new ArrayList<>();
        payloads.add(nativePayload(protocol, samplePacket((byte) 0)));
        payloads.add(nativePayload(protocol, samplePacket((byte) 0xFF)));
        payloads.add(new byte[0]);
        payloads.add(new byte[] {(byte) 0x80});
        payloads.add(new byte[] {0, 1, 2, 3});
        payloads.add(new byte[] {(byte) 0xFF, (byte) 0xFF});

        for (byte[] payload : payloads) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());
            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
            assertEquals("recorded connection state does not belong to the observer", result.reason());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsTheDecodedRespawnWithoutInspectingIt() {
        PacketRewriteResult result = new Id082RespawnPacketDefinition()
                .rewrite(new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
        assertEquals("recorded connection state does not belong to the observer", result.reason());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id082RespawnPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.CONFIGURATION, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.SERVERBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                    frame(new byte[0]), mismatch, codec, rewriter()));
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.rewrite(new Object(), mismatch, rewriter()));
        }
    }

    @Test
    void nativePlayCodecRoundTripsEveryDimensionAndRespawnFieldCombination() {
        int dataToKeep = 0;
        int testedCombinations = 0;
        for (Holder<DimensionType> dimensionType : dimensionTypes) {
            for (ResourceKey<Level> dimension : dimensions) {
                for (GameType gameType : GameType.values()) {
                    for (GameType previousGameType : PREVIOUS_GAME_TYPES) {
                        for (int flags = 0; flags < 4; flags++) {
                            for (Optional<GlobalPos> deathLocation : deathLocations) {
                                CommonPlayerSpawnInfo spawnInfo = new CommonPlayerSpawnInfo(
                                        dimensionType,
                                        dimension,
                                        SEEDS[testedCombinations % SEEDS.length],
                                        gameType,
                                        previousGameType,
                                        (flags & 1) != 0,
                                        (flags & 2) != 0,
                                        deathLocation,
                                        WORLD_INTS[testedCombinations % WORLD_INTS.length],
                                        WORLD_INTS[(testedCombinations / 3) % WORLD_INTS.length]);
                                ClientboundRespawnPacket expected = new ClientboundRespawnPacket(
                                        spawnInfo, (byte) dataToKeep);

                                assertNativeRoundTrip(expected);
                                assertKeepMaskSemantics(expected, dataToKeep);
                                dataToKeep = (dataToKeep + 1) & 0xFF;
                                testedCombinations++;
                            }
                        }
                    }
                }
            }
        }

        assertEquals(5_120, testedCombinations);
        assertEquals(0, dataToKeep);
    }

    @Test
    void nativeCodecUsesVarIntDimensionIdsAndWritesSpawnFieldsBeforeTheKeepMask() {
        ClientboundRespawnPacket expected = samplePacket((byte) 0xA5);
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            int dimensionTypeId = VarInt.read(wire);
            assertEquals(
                    registryAccess.lookupOrThrow(Registries.DIMENSION_TYPE)
                            .asHolderIdMap()
                            .getIdOrThrow(expected.commonPlayerSpawnInfo().dimensionType()),
                    dimensionTypeId);
            assertEquals(expected.commonPlayerSpawnInfo().dimension().identifier().toString(), readUtf(wire));
            assertEquals(expected.commonPlayerSpawnInfo().seed(), wire.readLong());
            assertEquals(expected.commonPlayerSpawnInfo().gameType().getId(), wire.readByte());
            assertEquals(
                    GameType.getNullableId(expected.commonPlayerSpawnInfo().previousGameType()),
                    wire.readByte());
            assertEquals(expected.commonPlayerSpawnInfo().isDebug(), wire.readBoolean());
            assertEquals(expected.commonPlayerSpawnInfo().isFlat(), wire.readBoolean());
            assertEquals(expected.commonPlayerSpawnInfo().lastDeathLocation().isPresent(), wire.readBoolean());
            expected.commonPlayerSpawnInfo().lastDeathLocation().ifPresent(globalPos -> {
                assertEquals(globalPos.dimension().identifier().toString(), readUtf(wire));
                assertEquals(globalPos.pos(), BlockPos.of(wire.readLong()));
            });
            assertEquals(expected.commonPlayerSpawnInfo().portalCooldown(), VarInt.read(wire));
            assertEquals(expected.commonPlayerSpawnInfo().seaLevel(), VarInt.read(wire));
            assertEquals(expected.dataToKeep(), wire.readByte());
            assertFalse(wire.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeCodecKeepsAllKeepMaskBitsAndTestsRequestedBitsIndependently() {
        assertEquals(0x01, ClientboundRespawnPacket.KEEP_ATTRIBUTE_MODIFIERS);
        assertEquals(0x02, ClientboundRespawnPacket.KEEP_ENTITY_DATA);
        assertEquals(0x03, ClientboundRespawnPacket.KEEP_ALL_DATA);

        for (int dataToKeep = 0; dataToKeep < 256; dataToKeep++) {
            ClientboundRespawnPacket packet = new ClientboundRespawnPacket(
                    samplePacket((byte) dataToKeep).commonPlayerSpawnInfo(), (byte) dataToKeep);
            for (int requestedMask = 0; requestedMask < 256; requestedMask++) {
                boolean expected = ((dataToKeep & requestedMask) != 0);
                assertEquals(expected, packet.shouldKeep((byte) requestedMask));
            }
        }
    }

    @Test
    void nativeCodecUsesVanillaFallbackForUnknownGameTypesAndTruthyBooleanBytes() {
        ClientboundRespawnPacket source = new ClientboundRespawnPacket(
                new CommonPlayerSpawnInfo(
                        dimensionTypes.getFirst(),
                        Level.OVERWORLD,
                        99L,
                        GameType.CREATIVE,
                        GameType.ADVENTURE,
                        false,
                        false,
                        deathLocations.get(1),
                        5,
                        -63),
                (byte) 0xF3);
        ByteBuf encoded = encodePacket(protocol, source);
        ByteBuf expectedCanonical = Unpooled.buffer();
        try {
            int[] fieldOffsets = spawnFieldOffsets(encoded.duplicate());
            encoded.setByte(fieldOffsets[0], 0x7F);
            encoded.setByte(fieldOffsets[1], 0x7F);
            encoded.setByte(fieldOffsets[2], 2);
            encoded.setByte(fieldOffsets[3], 0xFF);
            encoded.setByte(fieldOffsets[4], 0x7F);

            ClientboundRespawnPacket decoded = assertInstanceOf(
                    ClientboundRespawnPacket.class, decode(protocol, encoded));
            assertEquals(GameType.SURVIVAL, decoded.commonPlayerSpawnInfo().gameType());
            assertEquals(GameType.SURVIVAL, decoded.commonPlayerSpawnInfo().previousGameType());
            assertTrue(decoded.commonPlayerSpawnInfo().isDebug());
            assertTrue(decoded.commonPlayerSpawnInfo().isFlat());
            assertEquals(deathLocations.get(1), decoded.commonPlayerSpawnInfo().lastDeathLocation());
            assertEquals(source.dataToKeep(), decoded.dataToKeep());

            ClientboundRespawnPacket canonical = new ClientboundRespawnPacket(
                    new CommonPlayerSpawnInfo(
                            source.commonPlayerSpawnInfo().dimensionType(),
                            source.commonPlayerSpawnInfo().dimension(),
                            source.commonPlayerSpawnInfo().seed(),
                            GameType.SURVIVAL,
                            GameType.SURVIVAL,
                            true,
                            true,
                            deathLocations.get(1),
                            source.commonPlayerSpawnInfo().portalCooldown(),
                            source.commonPlayerSpawnInfo().seaLevel()),
                    source.dataToKeep());
            encode(protocol, expectedCanonical, canonical);
            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(readableBytes(expectedCanonical), readableBytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
            expectedCanonical.release();
        }
    }

    @Test
    void nativeCodecRejectsUnknownDimensionTypeAndEveryTruncatedPayloadPrefix() {
        ByteBuf unknownDimensionType = Unpooled.buffer();
        try {
            VarInt.write(unknownDimensionType, PACKET_ID);
            VarInt.write(unknownDimensionType, 127);
            assertDecodeFails(unknownDimensionType);
        } finally {
            unknownDimensionType.release();
        }

        ByteBuf encoded = encodePacket(protocol, samplePacket((byte) 0xFF));
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            byte[] payload = readableBytes(wire);
            for (int length = 0; length < payload.length; length++) {
                ByteBuf truncated = Unpooled.buffer();
                try {
                    VarInt.write(truncated, PACKET_ID);
                    truncated.writeBytes(Arrays.copyOf(payload, length));
                    assertDecodeFails(truncated);
                } finally {
                    truncated.release();
                }
            }
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeCodecPreservesPacketFramingAcrossPresentAndAbsentDeathLocations() {
        ClientboundRespawnPacket first = new ClientboundRespawnPacket(
                spawnInfo(deathLocations.getFirst()), (byte) 0);
        ClientboundRespawnPacket second = new ClientboundRespawnPacket(
                spawnInfo(deathLocations.getLast()), (byte) 0xFF);
        ByteBuf wire = Unpooled.buffer();
        try {
            encode(protocol, wire, first);
            encode(protocol, wire, second);
            wire.writeByte(0x5A);

            ClientboundRespawnPacket decodedFirst = assertInstanceOf(
                    ClientboundRespawnPacket.class, decode(protocol, wire));
            ClientboundRespawnPacket decodedSecond = assertInstanceOf(
                    ClientboundRespawnPacket.class, decode(protocol, wire));
            assertEquals(first, decodedFirst);
            assertEquals(second, decodedSecond);
            assertEquals(0x5A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    private static void assertKeepMaskSemantics(ClientboundRespawnPacket packet, int dataToKeep) {
        assertEquals((dataToKeep & 0x01) != 0, packet.shouldKeep(ClientboundRespawnPacket.KEEP_ATTRIBUTE_MODIFIERS));
        assertEquals((dataToKeep & 0x02) != 0, packet.shouldKeep(ClientboundRespawnPacket.KEEP_ENTITY_DATA));
        assertEquals((dataToKeep & 0x03) != 0, packet.shouldKeep(ClientboundRespawnPacket.KEEP_ALL_DATA));
    }

    private static void assertNativeRoundTrip(ClientboundRespawnPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] originalWire = readableBytes(encoded);
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            int dimensionTypeId = VarInt.read(wire);
            assertEquals(
                    registryAccess.lookupOrThrow(Registries.DIMENSION_TYPE)
                            .asHolderIdMap()
                            .getIdOrThrow(expected.commonPlayerSpawnInfo().dimensionType()),
                    dimensionTypeId);
            assertEquals(expected.commonPlayerSpawnInfo().dimension().identifier().toString(), readUtf(wire));
            assertEquals(expected.commonPlayerSpawnInfo().seed(), wire.readLong());
            assertEquals(expected.commonPlayerSpawnInfo().gameType().getId(), wire.readByte());
            assertEquals(
                    GameType.getNullableId(expected.commonPlayerSpawnInfo().previousGameType()),
                    wire.readByte());
            assertEquals(expected.commonPlayerSpawnInfo().isDebug(), wire.readBoolean());
            assertEquals(expected.commonPlayerSpawnInfo().isFlat(), wire.readBoolean());
            boolean hasDeathLocation = wire.readBoolean();
            assertEquals(expected.commonPlayerSpawnInfo().lastDeathLocation().isPresent(), hasDeathLocation);
            if (hasDeathLocation) {
                GlobalPos actual = GlobalPos.of(
                        ResourceKey.create(Registries.DIMENSION, Identifier.parse(readUtf(wire))),
                        BlockPos.of(wire.readLong()));
                assertEquals(expected.commonPlayerSpawnInfo().lastDeathLocation().orElseThrow(), actual);
            }
            assertEquals(expected.commonPlayerSpawnInfo().portalCooldown(), VarInt.read(wire));
            assertEquals(expected.commonPlayerSpawnInfo().seaLevel(), VarInt.read(wire));
            assertEquals(expected.dataToKeep(), wire.readByte());
            assertFalse(wire.isReadable());

            ClientboundRespawnPacket decoded = assertInstanceOf(
                    ClientboundRespawnPacket.class, decode(protocol, encoded));
            assertEquals(expected, decoded);
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(originalWire, readableBytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static int[] spawnFieldOffsets(ByteBuf wire) {
        VarInt.read(wire);
        VarInt.read(wire);
        readUtf(wire);
        wire.skipBytes(Long.BYTES);
        int gameType = wire.readerIndex();
        wire.skipBytes(1);
        int previousGameType = wire.readerIndex();
        wire.skipBytes(1);
        int debug = wire.readerIndex();
        wire.skipBytes(1);
        int flat = wire.readerIndex();
        wire.skipBytes(1);
        int deathLocationPresent = wire.readerIndex();
        return new int[] {gameType, previousGameType, debug, flat, deathLocationPresent};
    }

    private static ClientboundRespawnPacket samplePacket(byte dataToKeep) {
        return new ClientboundRespawnPacket(spawnInfo(deathLocations.get(1)), dataToKeep);
    }

    private static CommonPlayerSpawnInfo spawnInfo(Optional<GlobalPos> deathLocation) {
        return new CommonPlayerSpawnInfo(
                dimensionTypes.getFirst(),
                Level.OVERWORLD,
                0x0123456789ABCDEFL,
                GameType.SURVIVAL,
                null,
                false,
                true,
                deathLocation,
                17,
                -63);
    }

    private static void assertDecodeFails(ByteBuf wire) {
        assertThrows(RuntimeException.class, () -> decode(protocol, wire));
    }

    private static byte[] nativePayload(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded));
            return readableBytes(encoded);
        } finally {
            encoded.release();
        }
    }

    private static ByteBuf encodePacket(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf output = Unpooled.buffer();
        try {
            encode(protocol, output, packet);
            return output;
        } catch (RuntimeException | Error failure) {
            output.release();
            throw failure;
        }
    }

    private static String readUtf(ByteBuf input) {
        int length = VarInt.read(input);
        String value = input.toString(input.readerIndex(), length, StandardCharsets.UTF_8);
        input.skipBytes(length);
        return value;
    }

    private static byte[] readableBytes(ByteBuf input) {
        byte[] bytes = new byte[input.readableBytes()];
        input.getBytes(input.readerIndex(), bytes);
        return bytes;
    }

    private static PacketDescriptor descriptor() {
        return descriptor(TYPE_NAME, PACKET_ID,
                PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            String typeName,
            int packetId,
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                typeName,
                phase,
                direction,
                packetId,
                PacketDisposition.STATEFUL,
                direction == PacketDescriptor.Direction.CLIENTBOUND,
                replayable,
                false,
                "paper-26.2/" + typeName,
                scope);
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

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes, AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local respawn must not encode during playback");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local respawn must not decode during playback");
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
}
