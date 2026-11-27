package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.effect;

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
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.Paper26_3PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3ClientboundPacketCatalog;
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
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.GeyserBaseParticleOptions;
import net.minecraft.core.particles.GeyserParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.core.particles.SculkChargeParticleOptions;
import net.minecraft.core.particles.ShriekParticleOption;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.particles.SpellParticleOption;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.EntityPositionSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id048LevelParticlesPacketDefinitionTest {

    private static final int PACKET_ID = 48;
    private static final String TYPE_NAME = "play.clientbound.minecraft:level_particles";
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
    void classifiesParticlesAsEphemeralIdentityBearingSceneEffects() {
        var definition = new Id048LevelParticlesPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x30, Id048LevelParticlesPacketDefinition.PACKET_ID);
        assertEquals("minecraft:level_particles", Id048LevelParticlesPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
    }

    @Test
    void registersExactlyOnceInEffectDomainCatalogAndPaperRegistry() {
        var domainMatches = EffectPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_3ClientboundPacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id048LevelParticlesPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id048LevelParticlesPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_3PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                descriptor.playbackScope());
        assertTrue(Paper26_3ClientboundPacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void preservesEveryBuiltInParticleTypeAndNativePayload() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id048LevelParticlesPacketDefinition();
        var context = rewriter();
        List<ParticleOptions> allOptions = allBuiltInParticleOptions();
        assertEquals(BuiltInRegistries.PARTICLE_TYPE.size(), allOptions.size());

        for (int index = 0; index < allOptions.size(); index++) {
            var packet = new ClientboundLevelParticlesPacket(
                    allOptions.get(index),
                    index % 2 == 0,
                    index % 3 == 0,
                    -index - 0.5,
                    index + 0.25,
                    -index * 1.5,
                    -0.0F,
                    index + 0.125F,
                    -index - 0.75F,
                    index + 0.5F,
                    index % 2 == 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE);
            assertNativeRoundTrip(protocol, packet);
            var expected = definition.read(packet);
            var rewritten = definition.rewrite(expected, context).value().orElseThrow();
            assertEquals(expected, rewritten);
        }
    }

    @Test
    void rewritesEntityVibrationDestinationAndPreservesBlockDestination() {
        var definition = new Id048LevelParticlesPacketDefinition();
        var context = rewriter();
        int recordedEntityId = 77;
        float entityYOffset = -0.75F;
        int arrivalTicks = 23;
        var entityVibration = new VibrationParticleOption(
                entityPositionSource(recordedEntityId, entityYOffset), arrivalTicks);
        var entityPacket = packet(entityVibration);

        var rewrittenEntity = definition.rewrite(
                definition.read(entityPacket), context).value().orElseThrow();
        VibrationParticleOption mapped = assertInstanceOf(
                VibrationParticleOption.class, rewrittenEntity.particle());
        EntityPositionSource mappedDestination = assertInstanceOf(
                EntityPositionSource.class, mapped.getDestination());
        assertEquals(context.mapEntityId(recordedEntityId), entityId(mappedDestination));
        assertEquals(entityYOffset, yOffset(mappedDestination));
        assertEquals(arrivalTicks, mapped.getArrivalInTicks());
        assertEquals(definition.read(entityPacket).x(), rewrittenEntity.x());
        assertEquals(definition.read(entityPacket).count(), rewrittenEntity.count());

        BlockPos blockTarget = new BlockPos(-7, 80, 12);
        var blockVibration = new VibrationParticleOption(
                new BlockPositionSource(blockTarget), 31);
        var blockPacket = packet(blockVibration);
        var rewrittenBlock = definition.rewrite(
                definition.read(blockPacket), context).value().orElseThrow();
        VibrationParticleOption preserved = assertInstanceOf(
                VibrationParticleOption.class, rewrittenBlock.particle());
        BlockPositionSource preservedDestination = assertInstanceOf(
                BlockPositionSource.class, preserved.getDestination());
        assertEquals(blockTarget, preservedDestination.pos());
        assertEquals(31, preserved.getArrivalInTicks());

        var invalidEntityVibration = packet(new VibrationParticleOption(
                entityPositionSource(recordedEntityId, Float.NaN), 8));
        assertThrows(IncompatibleAdapterException.class, () -> definition.rewrite(
                definition.read(invalidEntityVibration), context));
    }

    @Test
    void rebuildsAndEncodesCompleteParticlePacketDuringPlayback() {
        var definition = new Id048LevelParticlesPacketDefinition();
        var original = new ClientboundLevelParticlesPacket(
                new VibrationParticleOption(entityPositionSource(501, 1.25F), 45),
                true,
                false,
                -123.5,
                76.25,
                999.0,
                -2.5F,
                3.75F,
                -0.125F,
                1.5F,
                Integer.MIN_VALUE);
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = codec(original, decodes, encodes);
        var context = rewriter();

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec, context);
        ClientboundLevelParticlesPacket rebuilt = assertInstanceOf(
                ClientboundLevelParticlesPacket.class, result.packet().orElseThrow());
        VibrationParticleOption vibration = assertInstanceOf(
                VibrationParticleOption.class, rebuilt.particle());
        EntityPositionSource destination = assertInstanceOf(
                EntityPositionSource.class, vibration.getDestination());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertNotSame(original, rebuilt);
        assertEquals(context.mapEntityId(501), entityId(destination));
        assertEquals(1.25F, yOffset(destination));
        assertEquals(45, vibration.getArrivalInTicks());
        assertEquals(original.overrideLimiter(), rebuilt.overrideLimiter());
        assertEquals(original.alwaysShow(), rebuilt.alwaysShow());
        assertEquals(original.x(), rebuilt.x());
        assertEquals(original.y(), rebuilt.y());
        assertEquals(original.z(), rebuilt.z());
        assertEquals(Float.floatToRawIntBits(original.xDist()),
                Float.floatToRawIntBits(rebuilt.xDist()));
        assertEquals(Float.floatToRawIntBits(original.yDist()),
                Float.floatToRawIntBits(rebuilt.yDist()));
        assertEquals(Float.floatToRawIntBits(original.zDist()),
                Float.floatToRawIntBits(rebuilt.zDist()));
        assertEquals(Float.floatToRawIntBits(original.xMaxSpeed()),
                Float.floatToRawIntBits(rebuilt.xMaxSpeed()));
        assertEquals(Float.floatToRawIntBits(original.yMaxSpeed()),
                Float.floatToRawIntBits(rebuilt.yMaxSpeed()));
        assertEquals(Float.floatToRawIntBits(original.zMaxSpeed()),
                Float.floatToRawIntBits(rebuilt.zMaxSpeed()));
        assertEquals(original.randomizationType(), rebuilt.randomizationType());
        assertEquals(original.count(), rebuilt.count());
        assertEquals(PACKET_ID, definition.encode(rebuilt, descriptor(), codec).packetId());
        assertEquals(1, decodes.get());
        assertEquals(1, encodes.get());
    }

    @Test
    void acceptsSignedCountAndFiniteRangesButRejectsNonFiniteSpatialScalars() {
        var definition = new Id048LevelParticlesPacketDefinition();

        for (int count : new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
            var value = new Id048LevelParticlesPacketDefinition.Value(
                    true,
                    true,
                    -Double.MAX_VALUE,
                    0.0,
                    Double.MAX_VALUE,
                    -Float.MAX_VALUE,
                    -0.0F,
                    Float.MAX_VALUE,
                    0.0F,
                    -0.0F,
                    Float.MAX_VALUE,
                    count,
                    ClientboundLevelParticlesPacket.RandomizationType.ALTERNATIVE_WITH_SPEED,
                    ParticleTypes.FLAME);
            assertEquals(count, definition.rewrite(value, rewriter())
                    .value().orElseThrow().count());
        }

        double[] invalidDoubles = {
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        };
        for (double invalid : invalidDoubles) {
            assertInvalid(invalid, 0.0, 0.0, 0.0F, 0.0F, 0.0F, 0.0F);
            assertInvalid(0.0, invalid, 0.0, 0.0F, 0.0F, 0.0F, 0.0F);
            assertInvalid(0.0, 0.0, invalid, 0.0F, 0.0F, 0.0F, 0.0F);
        }
        float[] invalidFloats = {
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY
        };
        for (float invalid : invalidFloats) {
            assertInvalid(0.0, 0.0, 0.0, invalid, 0.0F, 0.0F, 0.0F);
            assertInvalid(0.0, 0.0, 0.0, 0.0F, invalid, 0.0F, 0.0F);
            assertInvalid(0.0, 0.0, 0.0, 0.0F, 0.0F, invalid, 0.0F);
            assertInvalid(0.0, 0.0, 0.0, 0.0F, 0.0F, 0.0F, invalid);
        }
    }

    @Test
    void nativeProtocolUsesPacketFortyEightAndRejectsEveryTruncatedPrefix() {
        ProtocolInfo<?> protocol = protocol();
        var packet = new ClientboundLevelParticlesPacket(
                new VibrationParticleOption(entityPositionSource(314, 0.875F), 18),
                true,
                false,
                -123.25,
                64.5,
                91.75,
                -1.25F,
                2.5F,
                -3.75F,
                0.625F,
                7);
        ByteBuf encoded = encodePacket(protocol, packet);
        byte[] bytes;
        try {
            bytes = new byte[encoded.readableBytes()];
            encoded.getBytes(encoded.readerIndex(), bytes);
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            // Paper 26.3 writes the particle first and the count as a VarInt,
            // followed by the randomization type.
            ParticleOptions particle = ParticleTypes.STREAM_CODEC.decode(new RegistryFriendlyByteBuf(
                    wire, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
            assertEquals(packet.particle().getType(), particle.getType());
            assertEquals(true, wire.readBoolean());
            assertEquals(false, wire.readBoolean());
            assertEquals(packet.x(), wire.readDouble());
            assertEquals(packet.y(), wire.readDouble());
            assertEquals(packet.z(), wire.readDouble());
            assertEquals(Float.floatToRawIntBits(packet.xDist()),
                    Float.floatToRawIntBits(wire.readFloat()));
            assertEquals(Float.floatToRawIntBits(packet.yDist()),
                    Float.floatToRawIntBits(wire.readFloat()));
            assertEquals(Float.floatToRawIntBits(packet.zDist()),
                    Float.floatToRawIntBits(wire.readFloat()));
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(Float.floatToRawIntBits(0.625F),
                        Float.floatToRawIntBits(wire.readFloat()));
            }
            assertEquals(packet.count(), readVarInt(wire));
            assertEquals(packet.randomizationType().ordinal(), readVarInt(wire));
            assertFalse(wire.isReadable());
        } finally {
            encoded.release();
        }

        for (int length = 0; length < bytes.length; length++) {
            assertMalformed(protocol, Arrays.copyOf(bytes, length));
        }
    }

    private static void assertInvalid(
            double x,
            double y,
            double z,
            float xDist,
            float yDist,
            float zDist,
            float maxSpeed) {
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id048LevelParticlesPacketDefinition.Value(
                        false,
                        false,
                        x,
                        y,
                        z,
                        xDist,
                        yDist,
                        zDist,
                        maxSpeed,
                        maxSpeed,
                        maxSpeed,
                        1,
                        ClientboundLevelParticlesPacket.RandomizationType.DEFAULT,
                        ParticleTypes.FLAME));
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            ClientboundLevelParticlesPacket expected) {
        byte[] originalWire = encodePacketBytes(protocol, expected);
        ByteBuf encoded = Unpooled.wrappedBuffer(originalWire);
        try {
            ByteBuf header = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(header));
            ClientboundLevelParticlesPacket decoded = assertInstanceOf(
                    ClientboundLevelParticlesPacket.class, decode(protocol, encoded));
            assertEquals(expected.overrideLimiter(), decoded.overrideLimiter());
            assertEquals(expected.alwaysShow(), decoded.alwaysShow());
            assertEquals(expected.x(), decoded.x());
            assertEquals(expected.y(), decoded.y());
            assertEquals(expected.z(), decoded.z());
            assertEquals(Float.floatToRawIntBits(expected.xDist()),
                    Float.floatToRawIntBits(decoded.xDist()));
            assertEquals(Float.floatToRawIntBits(expected.yDist()),
                    Float.floatToRawIntBits(decoded.yDist()));
            assertEquals(Float.floatToRawIntBits(expected.zDist()),
                    Float.floatToRawIntBits(decoded.zDist()));
            assertEquals(Float.floatToRawIntBits(expected.xMaxSpeed()),
                    Float.floatToRawIntBits(decoded.xMaxSpeed()));
            assertEquals(Float.floatToRawIntBits(expected.yMaxSpeed()),
                    Float.floatToRawIntBits(decoded.yMaxSpeed()));
            assertEquals(Float.floatToRawIntBits(expected.zMaxSpeed()),
                    Float.floatToRawIntBits(decoded.zMaxSpeed()));
            assertEquals(expected.randomizationType(), decoded.randomizationType());
            assertEquals(expected.count(), decoded.count());
            assertEquals(expected.particle().getType(), decoded.particle().getType());
            assertArrayEquals(originalWire, encodePacketBytes(protocol, decoded));
        } finally {
            encoded.release();
        }
    }

    private static List<ParticleOptions> allBuiltInParticleOptions() {
        ArrayList<ParticleOptions> options = new ArrayList<>();
        for (ParticleType<?> type : BuiltInRegistries.PARTICLE_TYPE) {
            if (type instanceof SimpleParticleType simple) {
                options.add(simple);
            } else if (type == ParticleTypes.BLOCK
                    || type == ParticleTypes.BLOCK_MARKER
                    || type == ParticleTypes.FALLING_DUST
                    || type == ParticleTypes.DUST_PILLAR
                    || type == ParticleTypes.BLOCK_CRUMBLE) {
                options.add(new BlockParticleOption(
                        cast(type), Blocks.STONE.defaultBlockState()));
            } else if (type == ParticleTypes.GEYSER || type == ParticleTypes.GEYSER_PLUME) {
                options.add(new GeyserParticleOptions(cast(type), 7));
            } else if (type == ParticleTypes.GEYSER_BASE || type == ParticleTypes.GEYSER_POOF) {
                options.add(new GeyserBaseParticleOptions(cast(type), 5, 0.625F));
            } else if (type == ParticleTypes.DRAGON_BREATH) {
                options.add(PowerParticleOption.create(cast(type), 0.875F));
            } else if (type == ParticleTypes.DUST) {
                options.add(new DustParticleOptions(0xFF223344, 1.25F));
            } else if (type == ParticleTypes.DUST_COLOR_TRANSITION) {
                options.add(new DustColorTransitionOptions(0xFF112233, 0xFF445566, 0.75F));
            } else if (type == ParticleTypes.EFFECT || type == ParticleTypes.INSTANT_EFFECT) {
                options.add(SpellParticleOption.create(cast(type), 0xFF334455, 0.5F));
            } else if (type == ParticleTypes.ENTITY_EFFECT
                    || type == ParticleTypes.TINTED_LEAVES
                    || type == ParticleTypes.FLASH) {
                options.add(ColorParticleOption.create(cast(type), 0xFF12ABCD));
            } else if (type == ParticleTypes.SCULK_CHARGE) {
                options.add(new SculkChargeParticleOptions(1.5F));
            } else if (type == ParticleTypes.ITEM) {
                options.add(new ItemParticleOption(cast(type), Items.EMERALD));
            } else if (type == ParticleTypes.VIBRATION) {
                options.add(new VibrationParticleOption(
                        new BlockPositionSource(new BlockPos(-4, 5, 6)), 19));
            } else if (type == ParticleTypes.TRAIL) {
                options.add(new TrailParticleOption(new Vec3(-12.5, 7.25, 99.0), 0xFF778899, 27));
            } else if (type == ParticleTypes.SHRIEK) {
                options.add(new ShriekParticleOption(13));
            } else {
                throw new AssertionError("No test payload builder for particle type "
                        + BuiltInRegistries.PARTICLE_TYPE.getKey(type));
            }
        }
        return List.copyOf(options);
    }

    @SuppressWarnings("unchecked")
    private static <T extends ParticleOptions> ParticleType<T> cast(ParticleType<?> type) {
        return (ParticleType<T>) type;
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
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
    }

    private static ClientboundLevelParticlesPacket packet(ParticleOptions particle) {
        return new ClientboundLevelParticlesPacket(
                particle, true, false, -123.5, 76.25, 999.0,
                -2.5F, 3.75F, -0.125F, 1.5F, Integer.MIN_VALUE);
    }

    private static EntityPositionSource entityPositionSource(int entityId, float yOffset) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(entityId);
            buffer.writeFloat(yOffset);
            return EntityPositionSource.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static int entityId(EntityPositionSource source) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            EntityPositionSource.STREAM_CODEC.encode(buffer, source);
            return buffer.readVarInt();
        } finally {
            buffer.release();
        }
    }

    private static float yOffset(EntityPositionSource source) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            EntityPositionSource.STREAM_CODEC.encode(buffer, source);
            buffer.readVarInt();
            return buffer.readFloat();
        } finally {
            buffer.release();
        }
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
                "paper-26.3/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[0]);
    }

    private static Paper26_3ReplayPacketRewriter rewriter() {
        return new Paper26_3ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketCodec<Object> codec(
            ClientboundLevelParticlesPacket decoded,
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                assertInstanceOf(ClientboundLevelParticlesPacket.class, packet);
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }
}
