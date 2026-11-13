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
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.level.block.LevelEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id046LevelEventPacketDefinitionTest {

    private static final int PACKET_ID = 46;
    private static final String TYPE_NAME = "play.clientbound.minecraft:level_event";
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
    void classifiesOneShotWorldEventsAsSafeReplayableButNotCheckpointState() {
        var definition = new Id046LevelEventPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x2E, Id046LevelEventPacketDefinition.PACKET_ID);
        assertEquals("minecraft:level_event", Id046LevelEventPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
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
        assertInstanceOf(Id046LevelEventPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id046LevelEventPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void preservesEveryVersionPinnedEffectAndUnknownSignedIdentifiers() throws Exception {
        var definition = new Id046LevelEventPacketDefinition();
        int[] effectIds = publicEffectIds();
        assertTrue(effectIds.length > 50, "expected the complete Paper 26.2 LevelEvent constant set");

        int[] eventSpecificData = {
            Integer.MIN_VALUE, -1, 0, 1, 5, 42, 0x7FA1FF, Integer.MAX_VALUE
        };
        BlockPos[] positions = {
            new BlockPos(-33_554_432, -2048, -33_554_432),
            new BlockPos(33_554_431, 2047, 33_554_431)
        };

        for (int index = 0; index < effectIds.length; index++) {
            var packet = new ClientboundLevelEventPacket(
                    effectIds[index],
                    positions[index % positions.length],
                    eventSpecificData[index % eventSpecificData.length],
                    index % 2 == 0);
            assertRoundTripThroughDefinition(definition, packet);
        }

        for (int unknownId : new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
            for (int data : eventSpecificData) {
                assertRoundTripThroughDefinition(
                        definition,
                        new ClientboundLevelEventPacket(
                                unknownId, new BlockPos(-17, 255, 29), data, data % 2 == 0));
            }
        }
    }

    @Test
    void preservesDocumentedDataVariantsWithoutAssumingOneSharedMeaning() {
        var definition = new Id046LevelEventPacketDefinition();
        int[][] examples = {
            {LevelEvent.SOUND_PLAY_JUKEBOX_SONG, 12}, // Item registry ID for a record.
            {LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, 42}, // Particle count.
            {LevelEvent.PARTICLES_SHOOT_SMOKE, 5}, // Direction enum.
            {LevelEvent.PARTICLES_DESTROY_BLOCK, 0x1FFFF}, // Global block-state ID.
            {LevelEvent.PARTICLES_SPELL_POTION_SPLASH, 0x7FA1FF}, // RGB color.
            {LevelEvent.PARTICLES_TRIAL_SPAWNER_DETECT_PLAYER, Integer.MAX_VALUE}, // Player count.
            {LevelEvent.PARTICLES_TRIAL_SPAWNER_DETECT_PLAYER_OMINOUS, Integer.MIN_VALUE}
        };

        for (int[] example : examples) {
            for (boolean global : new boolean[] {false, true}) {
                assertRoundTripThroughDefinition(
                        definition,
                        new ClientboundLevelEventPacket(
                                example[0], new BlockPos(12, -64, -34), example[1], global));
            }
        }
    }

    @Test
    void defensivelyCopiesMutablePositionsAndRejectsNullPositions() {
        var source = new BlockPos.MutableBlockPos(1, 2, 3);
        var value = new Id046LevelEventPacketDefinition.Value(2001, source, 7, false);
        source.set(4, 5, 6);

        assertEquals(new BlockPos(1, 2, 3), value.position());
        assertThrows(
                IncompatibleAdapterException.class,
                () -> new Id046LevelEventPacketDefinition.Value(2001, null, 7, false));
    }

    @Test
    void playbackRebuildsAndEncodesAllFieldsWithoutChangingGlobalSemantics() {
        var definition = new Id046LevelEventPacketDefinition();
        var original = new ClientboundLevelEventPacket(
                LevelEvent.PARTICLES_DESTROY_BLOCK,
                new BlockPos(-12_345, -2048, 67_890),
                Integer.MIN_VALUE,
                true);
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = codec(original, decodes, encodes);

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec, rewriter());
        ClientboundLevelEventPacket rebuilt = assertInstanceOf(
                ClientboundLevelEventPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertNotSame(original, rebuilt);
        assertEquals(definition.read(original), definition.read(rebuilt));
        assertEquals(PACKET_ID, definition.encode(rebuilt, descriptor(), codec).packetId());
        assertEquals(1, decodes.get());
        assertEquals(1, encodes.get());
    }

    @Test
    void nativeProtocolPreservesFieldOrderWidthsIdsCoordinatesAndGlobalFlag() {
        ProtocolInfo<?> protocol = protocol();
        BlockPos[] positions = {
            BlockPos.ZERO,
            new BlockPos(-33_554_432, -2048, -33_554_432),
            new BlockPos(33_554_431, 2047, 33_554_431)
        };
        int[] ids = {LevelEvent.SOUND_PLAY_JUKEBOX_SONG, Integer.MIN_VALUE, Integer.MAX_VALUE};
        int[] data = {12, Integer.MIN_VALUE, Integer.MAX_VALUE};

        for (int index = 0; index < positions.length; index++) {
            for (boolean global : new boolean[] {false, true}) {
                assertNativeRoundTrip(
                        protocol,
                        new ClientboundLevelEventPacket(
                                ids[index], positions[index], data[index], global));
            }
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPacketPrefix() {
        ProtocolInfo<?> protocol = protocol();
        byte[] complete = encodePacketBytes(
                protocol,
                new ClientboundLevelEventPacket(
                        LevelEvent.PARTICLES_DESTROY_BLOCK,
                        new BlockPos(-123, -63, 456),
                        0x7FA1FF,
                        true));

        for (int length = 0; length < complete.length; length++) {
            assertMalformed(protocol, Arrays.copyOf(complete, length));
        }
    }

    private static void assertRoundTripThroughDefinition(
            Id046LevelEventPacketDefinition definition,
            ClientboundLevelEventPacket packet) {
        var expected = definition.read(packet);
        PacketRewriteResult rewritten = definition.rewrite(packet, descriptor(), rewriter());
        ClientboundLevelEventPacket rebuilt = assertInstanceOf(
                ClientboundLevelEventPacket.class, rewritten.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, rewritten.decision());
        assertNotSame(packet, rebuilt);
        assertEquals(expected, definition.read(rebuilt));
    }

    private static int[] publicEffectIds() throws IllegalAccessException {
        return Arrays.stream(LevelEvent.class.getDeclaredFields())
                .filter(field -> field.getType() == int.class)
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> Modifier.isFinal(field.getModifiers()))
                .mapToInt(field -> readInt(field))
                .toArray();
    }

    private static int readInt(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException failure) {
            throw new AssertionError("Could not read LevelEvent constant " + field.getName(), failure);
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            ClientboundLevelEventPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            assertEquals(expected.getType(), wire.readInt());
            assertEquals(expected.getPos().asLong(), wire.readLong());
            assertEquals(expected.getData(), wire.readInt());
            assertEquals(expected.isGlobalEvent(), wire.readBoolean());
            assertFalse(wire.isReadable());

            ClientboundLevelEventPacket decoded = assertInstanceOf(
                    ClientboundLevelEventPacket.class, decode(protocol, encoded));
            assertEquals(expected.getType(), decoded.getType());
            assertEquals(expected.getPos(), decoded.getPos());
            assertEquals(expected.getData(), decoded.getData());
            assertEquals(expected.isGlobalEvent(), decoded.isGlobalEvent());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
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
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
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
            ClientboundLevelEventPacket decoded,
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                assertInstanceOf(ClientboundLevelEventPacket.class, packet);
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
}
