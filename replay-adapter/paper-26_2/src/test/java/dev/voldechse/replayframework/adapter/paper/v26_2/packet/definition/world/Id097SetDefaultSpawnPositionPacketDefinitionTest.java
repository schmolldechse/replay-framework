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
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id097SetDefaultSpawnPositionPacketDefinitionTest {

    private static final int PACKET_ID = 97;
    private static final String TYPE_NAME =
            "play.clientbound.minecraft:set_default_spawn_position";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final int MIN_HORIZONTAL = -BlockPos.MAX_HORIZONTAL_COORDINATE - 1;
    private static final int MIN_PACKED_Y = -(1 << (BlockPos.PACKED_Y_LENGTH - 1));
    private static final int MAX_PACKED_Y = (1 << (BlockPos.PACKED_Y_LENGTH - 1)) - 1;
    private static final int[][] ANGLE_BITS = {
        {0x00000000, 0x00000000},
        {0x80000000, 0x80000000},
        {Float.floatToRawIntBits(-180.0F), Float.floatToRawIntBits(-90.0F)},
        {Float.floatToRawIntBits(180.0F), Float.floatToRawIntBits(90.0F)},
        {Float.floatToRawIntBits(Float.MAX_VALUE), Float.floatToRawIntBits(Float.MIN_VALUE)},
        {0x7F800000, 0xFF800000},
        {0x7FC01234, 0xFFC05678}
    };

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesDefaultSpawnAsSafeDimensionCheckpointState() {
        var definition = new Id097SetDefaultSpawnPositionPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x61, Id097SetDefaultSpawnPositionPacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_default_spawn_position",
                Id097SetDefaultSpawnPositionPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE,
                definition.classification().playbackScope());
        assertEquals(Paper26_2CheckpointEncoder.CheckpointPacketFamily.DIMENSION_SPAWN,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));

        assertEquals(1, WorldPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .count());
        assertEquals(1, Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .count());

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
    void nativeCodecPreservesDimensionsPackedBlockPositionAndRawAngleBits() {
        ProtocolInfo<?> protocol = protocol();
        ResourceKey<Level> customDimension = ResourceKey.create(
                Registries.DIMENSION, Identifier.parse("replay:custom_dimension"));
        List<ResourceKey<Level>> dimensions = List.of(
                Level.OVERWORLD, Level.NETHER, Level.END, customDimension);
        List<BlockPos> positions = List.of(
                BlockPos.ZERO,
                new BlockPos(-31_337, 128, 77_777),
                new BlockPos(
                        BlockPos.MAX_HORIZONTAL_COORDINATE,
                        MAX_PACKED_Y,
                        MIN_HORIZONTAL),
                new BlockPos(
                        MIN_HORIZONTAL,
                        MIN_PACKED_Y,
                        BlockPos.MAX_HORIZONTAL_COORDINATE));

        for (ResourceKey<Level> dimension : dimensions) {
            for (BlockPos position : positions) {
                for (int[] angleBits : ANGLE_BITS) {
                    ClientboundSetDefaultSpawnPositionPacket original = packet(
                            dimension,
                            position,
                            Float.intBitsToFloat(angleBits[0]),
                            Float.intBitsToFloat(angleBits[1]));
                    assertNativeRoundTrip(protocol, original);
                }
            }
        }
    }

    @Test
    void nativeFieldOrderIsGlobalPositionThenYawThenPitch() {
        ProtocolInfo<?> protocol = protocol();
        ResourceKey<Level> dimension = ResourceKey.create(
                Registries.DIMENSION, Identifier.parse("replay:wire_order"));
        BlockPos position = new BlockPos(-123_456, -987, 234_567);
        float yaw = Float.intBitsToFloat(0x7FC01234);
        float pitch = Float.intBitsToFloat(0xFFC05678);
        ByteBuf encoded = encode(protocol, packet(dimension, position, yaw, pitch));
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            GlobalPos globalPos = GlobalPos.STREAM_CODEC.decode(fields);
            assertEquals(dimension, globalPos.dimension());
            assertEquals(position.getX(), globalPos.pos().getX());
            assertEquals(position.getY(), globalPos.pos().getY());
            assertEquals(position.getZ(), globalPos.pos().getZ());
            assertFloatBits(yaw, fields.readFloat());
            assertFloatBits(pitch, fields.readFloat());
            assertFalse(fields.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void playbackRebuildsTheDimensionScopedSpawnWithoutNormalizingAngles() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id097SetDefaultSpawnPositionPacketDefinition();
        PacketCodec<Object> codec = codec(protocol);
        ResourceKey<Level> dimension = ResourceKey.create(
                Registries.DIMENSION, Identifier.parse("replay:playback_world"));
        ClientboundSetDefaultSpawnPositionPacket original = packet(
                dimension,
                new BlockPos(-8_000_000, 1_204, 7_000_000),
                Float.intBitsToFloat(0x7FC01234),
                Float.POSITIVE_INFINITY);
        byte[] payload = nativePayload(protocol, original);

        PacketRewriteResult result = definition.handlePlayback(
                frame(payload), descriptor(), codec, rewriter());
        ClientboundSetDefaultSpawnPositionPacket rebuilt = assertInstanceOf(
                ClientboundSetDefaultSpawnPositionPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertRespawnDataEquals(original.respawnData(), rebuilt.respawnData());
        var encoded = definition.encode(rebuilt, descriptor(), codec);
        assertEquals(PACKET_ID, encoded.packetId());
        assertArrayEquals(payload, encoded.payload());
    }

    @Test
    void nativeCodecRejectsTruncatedAndInvalidDimensionPayloads() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundSetDefaultSpawnPositionPacket source = packet(
                ResourceKey.create(Registries.DIMENSION, Identifier.parse("replay:truncation_test")),
                new BlockPos(10, 64, -20),
                37.0F,
                -12.0F);
        ByteBuf complete = encode(protocol, source);
        try {
            byte[] body = payload(complete);
            for (int prefixLength = 0; prefixLength < body.length; prefixLength++) {
                int truncatedLength = prefixLength;
                assertNativeDecodeFails(protocol,
                        buffer -> buffer.writeBytes(body, 0, truncatedLength));
            }
        } finally {
            complete.release();
        }

        assertNativeDecodeFails(protocol, buffer -> {
            FriendlyByteBuf invalid = new FriendlyByteBuf(buffer);
            invalid.writeUtf("Invalid Dimension Id");
            invalid.writeLong(0L);
            invalid.writeFloat(0.0F);
            invalid.writeFloat(0.0F);
        });
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = Unpooled.buffer();
        try {
            wire.writeBytes(packetWire(protocol, packet(
                    Level.NETHER, new BlockPos(-3, 71, 19), 12.0F, -4.0F)));
            wire.writeByte(0x7A);

            ClientboundSetDefaultSpawnPositionPacket decoded =
                    assertInstanceOf(ClientboundSetDefaultSpawnPositionPacket.class,
                            decodePacket(protocol, wire));
            assertEquals(Level.NETHER, decoded.respawnData().dimension());
            assertEquals(-3, decoded.respawnData().pos().getX());
            assertEquals(71, decoded.respawnData().pos().getY());
            assertEquals(19, decoded.respawnData().pos().getZ());
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void rejectsDescriptorMismatchesAndNonReplayableDescriptors() {
        var definition = new Id097SetDefaultSpawnPositionPacketDefinition();
        PacketCodec<Object> codec = codec(protocol());
        ClientboundSetDefaultSpawnPositionPacket original = packet(
                Level.OVERWORLD, new BlockPos(1, 64, 2), 0.0F, 0.0F);

        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame(new byte[0]),
                        descriptor(TYPE_NAME, PACKET_ID + 1, true), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame(new byte[0]),
                        descriptor("play.clientbound.minecraft:other", PACKET_ID, true),
                        codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame(nativePayload(protocol(), original)),
                        descriptor(TYPE_NAME, PACKET_ID, false), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.encode(original,
                        descriptor(TYPE_NAME, PACKET_ID + 1, true), codec));
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundSetDefaultSpawnPositionPacket original) {
        ByteBuf encoded = encode(protocol, original);
        try {
            byte[] expectedWire = bytes(encoded);
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            GlobalPos globalPos = GlobalPos.STREAM_CODEC.decode(fields);
            assertEquals(original.respawnData().dimension(), globalPos.dimension());
            assertEquals(original.respawnData().pos().getX(), globalPos.pos().getX());
            assertEquals(original.respawnData().pos().getY(), globalPos.pos().getY());
            assertEquals(original.respawnData().pos().getZ(), globalPos.pos().getZ());
            assertFloatBits(original.respawnData().yaw(), fields.readFloat());
            assertFloatBits(original.respawnData().pitch(), fields.readFloat());
            assertFalse(fields.isReadable());

            ClientboundSetDefaultSpawnPositionPacket decoded =
                    assertInstanceOf(ClientboundSetDefaultSpawnPositionPacket.class,
                    decodePacket(protocol, encoded));
            assertRespawnDataEquals(original.respawnData(), decoded.respawnData());

            var definition = new Id097SetDefaultSpawnPositionPacketDefinition();
            ClientboundSetDefaultSpawnPositionPacket rebuilt =
                    definition.rebuild(definition.read(decoded));
            ByteBuf reencoded = encode(protocol, rebuilt);
            try {
                assertArrayEquals(expectedWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static void assertRespawnDataEquals(
            LevelData.RespawnData expected, LevelData.RespawnData actual) {
        assertEquals(expected.dimension(), actual.dimension());
        assertEquals(expected.pos().getX(), actual.pos().getX());
        assertEquals(expected.pos().getY(), actual.pos().getY());
        assertEquals(expected.pos().getZ(), actual.pos().getZ());
        assertFloatBits(expected.yaw(), actual.yaw());
        assertFloatBits(expected.pitch(), actual.pitch());
    }

    private static void assertFloatBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }

    private static ClientboundSetDefaultSpawnPositionPacket packet(
            ResourceKey<Level> dimension, BlockPos position, float yaw, float pitch) {
        return new ClientboundSetDefaultSpawnPositionPacket(new LevelData.RespawnData(
                GlobalPos.of(dimension, position), yaw, pitch));
    }

    private static byte[] packetWire(
            ProtocolInfo<?> protocol, ClientboundSetDefaultSpawnPositionPacket packet) {
        ByteBuf wire = encode(protocol, packet);
        try {
            return bytes(wire);
        } finally {
            wire.release();
        }
    }

    private static byte[] nativePayload(
            ProtocolInfo<?> protocol, ClientboundSetDefaultSpawnPositionPacket packet) {
        ByteBuf wire = encode(protocol, packet);
        try {
            assertEquals(PACKET_ID, VarInt.read(wire));
            return bytes(wire);
        } finally {
            wire.release();
        }
    }

    private static byte[] payload(ByteBuf wire) {
        ByteBuf copy = wire.duplicate();
        assertEquals(PACKET_ID, VarInt.read(copy));
        return bytes(copy);
    }

    private static PacketCodec<Object> codec(ProtocolInfo<?> protocol) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                ClientboundSetDefaultSpawnPositionPacket nativePacket = assertInstanceOf(
                        ClientboundSetDefaultSpawnPositionPacket.class, packet);
                ByteBuf wire = Id097SetDefaultSpawnPositionPacketDefinitionTest.encode(
                        protocol, nativePacket);
                try {
                    assertEquals(PACKET_ID, VarInt.read(wire));
                    return new EncodedPacket(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            PACKET_ID,
                            bytes(wire));
                } finally {
                    wire.release();
                }
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                ByteBuf wire = Unpooled.buffer();
                try {
                    VarInt.write(wire, PACKET_ID);
                    wire.writeBytes(frame.payload());
                    return decodePacket(protocol, wire);
                } finally {
                    wire.release();
                }
            }
        };
    }

    private static ByteBuf encode(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            encodeNative(protocol, buffer, packet);
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

    private static void assertNativeDecodeFails(
            ProtocolInfo<?> protocol,
            java.util.function.Consumer<ByteBuf> payloadWriter) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            payloadWriter.accept(wire);
            assertThrows(RuntimeException.class, () -> decodePacket(protocol, wire));
        } finally {
            wire.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encodeNative(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decodePacket(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }
}
