package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

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
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.GeyserBaseParticleOptions;
import net.minecraft.core.particles.GeyserParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.core.particles.SculkChargeParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.particles.ShriekParticleOption;
import net.minecraft.core.particles.SpellParticleOption;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.EntityPositionSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id036ExplodePacketDefinitionTest {

    private static final int PACKET_ID = 36;
    private static final String TYPE_NAME = "play.clientbound.minecraft:explode";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Vec3 CENTER = new Vec3(123.25, -64.0, -809.5);
    private static final List<ParticleOptions> ALL_PARTICLE_OPTION_FAMILIES = List.of(
            ParticleTypes.EXPLOSION,
            new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
            ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFF12ABCD),
            new DustParticleOptions(0xFF223344, 1.25F),
            new DustColorTransitionOptions(0xFF112233, 0xFF445566, 0.75F),
            new GeyserBaseParticleOptions(ParticleTypes.GEYSER_BASE, 7, 0.625F),
            new GeyserParticleOptions(ParticleTypes.GEYSER, 3),
            new ItemParticleOption(ParticleTypes.ITEM, Items.EMERALD),
            PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 0.875F),
            new SculkChargeParticleOptions(1.5F),
            new ShriekParticleOption(13),
            SpellParticleOption.create(ParticleTypes.EFFECT, 0xFF334455, 0.5F),
            new TrailParticleOption(new Vec3(-12.5, 7.25, 99.0), 0xFF778899, 27),
            new VibrationParticleOption(new BlockPositionSource(new BlockPos(4, 5, 6)), 19),
            new VibrationParticleOption(entityPositionSource(77, 1.25F), 23));

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesExplosionAsEphemeralIdentityBearingSceneData() {
        var definition = new Id036ExplodePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x24, Id036ExplodePacketDefinition.PACKET_ID);
        assertEquals("minecraft:explode", Id036ExplodePacketDefinition.IDENTIFIER);
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
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id036ExplodePacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id036ExplodePacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES, descriptor.playbackScope());
    }

    @Test
    void removesPlayerKnockbackAndRemapsVibrationEntitySourcesAtEveryDepth() {
        var definition = new Id036ExplodePacketDefinition();
        var context = rewriter();
        int topEntityId = 77;
        int weightedEntityId = 88;
        var topVibration = new VibrationParticleOption(
                entityPositionSource(topEntityId, 1.25F), 23);
        var blockVibration = new VibrationParticleOption(
                new BlockPositionSource(new BlockPos(7, 8, 9)), 31);
        WeightedList<ExplosionParticleInfo> weighted = WeightedList.of(List.of(
                new Weighted<>(new ExplosionParticleInfo(blockVibration, 0.5F, 1.5F), 0),
                new Weighted<>(new ExplosionParticleInfo(
                        new VibrationParticleOption(
                                entityPositionSource(weightedEntityId, -0.5F), 37),
                        1.25F,
                        2.75F), 4)));
        ClientboundExplodePacket packet = new ClientboundExplodePacket(
                CENTER,
                6.5F,
                219,
                Optional.of(new Vec3(1.5, -0.25, 0.75)),
                topVibration,
                SoundEvents.GENERIC_EXPLODE,
                weighted);

        Id036ExplodePacketDefinition.Value rewritten = definition
                .rewrite(definition.read(packet), context)
                .value()
                .orElseThrow();

        assertEquals(CENTER, rewritten.center());
        assertEquals(6.5F, rewritten.radius());
        assertEquals(219, rewritten.blockCount());
        assertTrue(rewritten.playerKnockback().isEmpty());
        assertEquals(SoundEvents.GENERIC_EXPLODE, rewritten.explosionSound());
        assertEntityDestination(topEntityId, rewritten.explosionParticle(), context);

        List<Weighted<ExplosionParticleInfo>> entries = rewritten.blockParticles().unwrap();
        assertEquals(2, entries.size());
        assertEquals(0, entries.get(0).weight());
        assertEquals(4, entries.get(1).weight());
        assertEquals(0.5F, entries.get(0).value().scaling());
        assertEquals(1.5F, entries.get(0).value().speed());
        VibrationParticleOption blockDestination = assertInstanceOf(
                VibrationParticleOption.class, entries.get(0).value().particle());
        BlockPositionSource blockSource = assertInstanceOf(
                BlockPositionSource.class, blockDestination.getDestination());
        assertEquals(new BlockPos(7, 8, 9), blockSource.pos());
        assertEquals(1.25F, entries.get(1).value().scaling());
        assertEquals(2.75F, entries.get(1).value().speed());
        assertEntityDestination(weightedEntityId, entries.get(1).value().particle(), context);

        ClientboundExplodePacket rebuilt = definition.rebuild(rewritten);
        assertEquals(CENTER, rebuilt.center());
        assertEquals(219, rebuilt.blockCount());
        assertTrue(rebuilt.playerKnockback().isEmpty());
        assertEquals(rewritten.explosionParticle(), rebuilt.explosionParticle());
        assertEquals(rewritten.blockParticles(), rebuilt.blockParticles());
    }

    @Test
    void preservesEmptyKnockbackAndEmptyOrWeightedParticleLists() {
        var definition = new Id036ExplodePacketDefinition();
        var context = rewriter();

        for (WeightedList<ExplosionParticleInfo> particles : List.<WeightedList<ExplosionParticleInfo>>of(
                WeightedList.of(),
                WeightedList.of(new ExplosionParticleInfo(ParticleTypes.EXPLOSION, 1.0F, 1.0F)))) {
            var value = new Id036ExplodePacketDefinition.Value(
                    CENTER,
                    0.0F,
                    Integer.MIN_VALUE,
                    Optional.empty(),
                    ParticleTypes.EXPLOSION,
                    SoundEvents.GENERIC_EXPLODE,
                    particles);
            var rewritten = definition.rewrite(value, context).value().orElseThrow();
            assertTrue(rewritten.playerKnockback().isEmpty());
            assertEquals(Integer.MIN_VALUE, rewritten.blockCount());
            assertEquals(particles, rewritten.blockParticles());
        }
    }

    @Test
    void nativeProtocolRoundTripsAllBuiltInParticleTypesAndSoundHolderForms() {
        ProtocolInfo<?> protocol = protocol();
        List<ParticleOptions> allParticleOptions = allBuiltInParticleOptions();
        WeightedList<ExplosionParticleInfo> weighted = WeightedList.of(List.of(
                new Weighted<>(new ExplosionParticleInfo(ParticleTypes.EXPLOSION, 0.0F, 3.0F), 0),
                new Weighted<>(new ExplosionParticleInfo(allParticleOptions.get(2), 1.25F, 0.5F), 7)));
        Holder<SoundEvent> fixedRangeSound = Holder.direct(SoundEvent.createFixedRangeEvent(
                Identifier.withDefaultNamespace("replay_test_explosion"), 23.5F));

        for (int index = 0; index < allParticleOptions.size(); index++) {
            Holder<SoundEvent> sound = index % 2 == 0
                    ? SoundEvents.GENERIC_EXPLODE
                    : fixedRangeSound;
            Optional<Vec3> knockback = index % 2 == 0
                    ? Optional.empty()
                    : Optional.of(new Vec3(-0.125, 0.5, 8.25));
            ClientboundExplodePacket packet = new ClientboundExplodePacket(
                    CENTER,
                    index + 0.25F,
                    index * 17,
                    knockback,
                    allParticleOptions.get(index),
                    sound,
                    weighted);
            assertRoundTrip(protocol, packet);
        }

        for (ParticleOptions particle : allParticleOptions) {
            ClientboundExplodePacket weightedParticle = new ClientboundExplodePacket(
                    CENTER,
                    1.75F,
                    5,
                    Optional.empty(),
                    ParticleTypes.EXPLOSION,
                    SoundEvents.GENERIC_EXPLODE,
                    WeightedList.of(new ExplosionParticleInfo(particle, 2.25F, 0.375F)));
            assertRoundTrip(protocol, weightedParticle);
        }
    }

    @Test
    void rejectsNonFiniteExplosionAndWeightedParticleScalars() {
        var definition = new Id036ExplodePacketDefinition();
        WeightedList<ExplosionParticleInfo> ordinary = WeightedList.of(
                new ExplosionParticleInfo(ParticleTypes.EXPLOSION, 1.0F, 1.0F));

        for (double invalid : new double[] {
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        }) {
            assertInvalid(definition, new ClientboundExplodePacket(
                    new Vec3(invalid, 0.0, 0.0), 1.0F, 1, Optional.empty(),
                    ParticleTypes.EXPLOSION, SoundEvents.GENERIC_EXPLODE, ordinary));
            assertInvalid(definition, new ClientboundExplodePacket(
                    new Vec3(0.0, invalid, 0.0), 1.0F, 1, Optional.empty(),
                    ParticleTypes.EXPLOSION, SoundEvents.GENERIC_EXPLODE, ordinary));
            assertInvalid(definition, new ClientboundExplodePacket(
                    new Vec3(0.0, 0.0, invalid), 1.0F, 1, Optional.empty(),
                    ParticleTypes.EXPLOSION, SoundEvents.GENERIC_EXPLODE, ordinary));
            assertInvalid(definition, new ClientboundExplodePacket(
                    CENTER, 1.0F, 1, Optional.of(new Vec3(invalid, 0.0, 0.0)),
                    ParticleTypes.EXPLOSION, SoundEvents.GENERIC_EXPLODE, ordinary));
        }

        assertInvalid(definition, new ClientboundExplodePacket(
                CENTER, Float.NaN, 1, Optional.empty(), ParticleTypes.EXPLOSION,
                SoundEvents.GENERIC_EXPLODE, ordinary));
        assertInvalid(definition, new ClientboundExplodePacket(
                CENTER, 1.0F, 1, Optional.empty(), ParticleTypes.EXPLOSION,
                SoundEvents.GENERIC_EXPLODE,
                WeightedList.of(new ExplosionParticleInfo(ParticleTypes.EXPLOSION, Float.NaN, 1.0F))));
        assertInvalid(definition, new ClientboundExplodePacket(
                CENTER, 1.0F, 1, Optional.empty(), ParticleTypes.EXPLOSION,
                SoundEvents.GENERIC_EXPLODE,
                WeightedList.of(new ExplosionParticleInfo(ParticleTypes.EXPLOSION, 1.0F, Float.POSITIVE_INFINITY))));
    }

    @Test
    void playbackDropsKnockbackAndEncodesTheRebuiltExplosion() {
        var definition = new Id036ExplodePacketDefinition();
        var original = new ClientboundExplodePacket(
                CENTER,
                4.0F,
                5,
                Optional.of(new Vec3(5.0, 6.0, 7.0)),
                ParticleTypes.EXPLOSION,
                SoundEvents.GENERIC_EXPLODE,
                WeightedList.of());
        var codec = codec(original);

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec, rewriter());
        ClientboundExplodePacket rewritten = assertInstanceOf(
                ClientboundExplodePacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertTrue(rewritten.playerKnockback().isEmpty());
        assertEquals(PACKET_ID, definition.encode(rewritten, descriptor(), codec).packetId());
    }

    @Test
    void nativeProtocolUsesIdThirtySixAndRejectsEveryTruncatedBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, new byte[0]);
        ClientboundExplodePacket packet = new ClientboundExplodePacket(
                CENTER,
                2.5F,
                123,
                Optional.of(new Vec3(0.125, -0.25, 0.5)),
                ALL_PARTICLE_OPTION_FAMILIES.getFirst(),
                SoundEvents.GENERIC_EXPLODE,
                WeightedList.of(new ExplosionParticleInfo(ParticleTypes.EXPLOSION, 1.0F, 1.0F)));
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            assertEquals(CENTER.x, wire.readDouble());
            assertEquals(CENTER.y, wire.readDouble());
            assertEquals(CENTER.z, wire.readDouble());
            assertEquals(2.5F, wire.readFloat());
            assertEquals(123, wire.readInt());
            assertTrue(wire.readBoolean());
            assertEquals(0.125, wire.readDouble());
            assertEquals(-0.25, wire.readDouble());
            assertEquals(0.5, wire.readDouble());

            byte[] payload = new byte[wire.readableBytes()];
            ByteBuf body = encoded.duplicate();
            readVarInt(body);
            body.readBytes(payload);
            for (int length = 0; length < payload.length; length++) {
                assertMalformed(protocol, java.util.Arrays.copyOf(payload, length));
            }
        } finally {
            encoded.release();
        }
    }

    private static void assertEntityDestination(
            int recordedEntityId,
            ParticleOptions particle,
            Paper26_2ReplayPacketRewriter context) {
        VibrationParticleOption vibration = assertInstanceOf(VibrationParticleOption.class, particle);
        EntityPositionSource destination = assertInstanceOf(
                EntityPositionSource.class, vibration.getDestination());
        assertEquals(context.mapEntityId(recordedEntityId), entityId(destination));
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

    private static List<ParticleOptions> allBuiltInParticleOptions() {
        ArrayList<ParticleOptions> particles = new ArrayList<>(ALL_PARTICLE_OPTION_FAMILIES);
        for (ParticleType<?> type : BuiltInRegistries.PARTICLE_TYPE) {
            if (type instanceof SimpleParticleType simpleParticle) {
                particles.add(simpleParticle);
            }
        }
        return List.copyOf(particles);
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

    private static void assertRoundTrip(
            ProtocolInfo<?> protocol,
            ClientboundExplodePacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        byte[] originalWire = new byte[encoded.readableBytes()];
        encoded.getBytes(encoded.readerIndex(), originalWire);
        try {
            ByteBuf header = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(header));
            ClientboundExplodePacket decoded = assertInstanceOf(
                    ClientboundExplodePacket.class, decode(protocol, encoded));
            assertEquals(expected.center(), decoded.center());
            assertEquals(expected.radius(), decoded.radius());
            assertEquals(expected.blockCount(), decoded.blockCount());
            assertEquals(expected.playerKnockback(), decoded.playerKnockback());
            assertEquals(expected.explosionSound().value(), decoded.explosionSound().value());
            assertEquals(expected.explosionParticle().getType(), decoded.explosionParticle().getType());
            assertEquals(expected.blockParticles().unwrap().size(), decoded.blockParticles().unwrap().size());
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                byte[] actualWire = new byte[reencoded.readableBytes()];
                reencoded.readBytes(actualWire);
                assertArrayEquals(originalWire, actualWire);
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static void assertInvalid(
            Id036ExplodePacketDefinition definition,
            ClientboundExplodePacket packet) {
        assertThrows(IncompatibleAdapterException.class, () -> definition.read(packet));
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
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
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
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundExplodePacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundExplodePacket.class, packet);
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

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf input = Unpooled.buffer();
        try {
            writeVarInt(input, PACKET_ID);
            input.writeBytes(payload);
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
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
