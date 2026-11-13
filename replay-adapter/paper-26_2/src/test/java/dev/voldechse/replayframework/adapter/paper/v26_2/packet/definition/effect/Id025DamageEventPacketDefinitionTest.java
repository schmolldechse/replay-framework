package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
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
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id025DamageEventPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:damage_event";
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static TestRegistries registries;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        MappedRegistry<DamageType> damageTypes = new MappedRegistry<>(
                Registries.DAMAGE_TYPE, Lifecycle.stable());
        Holder<DamageType> generic = registerDamageType(damageTypes, "test_generic");
        Holder<DamageType> projectile = registerDamageType(damageTypes, "test_projectile");
        Holder<DamageType> magic = registerDamageType(damageTypes, "test_magic");
        List<Registry<?>> allRegistries = new ArrayList<>();
        BuiltInRegistries.REGISTRY.forEach(allRegistries::add);
        allRegistries.add(damageTypes);
        RegistryAccess.Frozen registryAccess =
                new RegistryAccess.ImmutableRegistryAccess(allRegistries).freeze();
        registries = new TestRegistries(registryAccess, damageTypes, List.of(generic, projectile, magic));
    }

    @Test
    void classifiesAndRegistersDamageEventAsAnEphemeralIdentityBearingEffect() {
        var definition = new Id025DamageEventPacketDefinition();

        assertEquals(25, definition.packetId());
        assertEquals(0x19, Id025DamageEventPacketDefinition.PACKET_ID);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
        assertEquals(
                TYPE_NAME,
                Paper26_2PacketCatalog.catalog().find(TYPE_NAME).orElseThrow().typeName());
    }

    @Test
    void rewritesEveryPresentIdentityAndPreservesAbsentSentinelsAndSourceType() {
        var definition = new Id025DamageEventPacketDefinition();
        var context = rewriter();
        Vec3 position = new Vec3(-12.5, 64.0, 0.125);
        ClientboundDamageEventPacket original = new ClientboundDamageEventPacket(
                100, registries.damageTypes().get(0), 200, 300, Optional.of(position));

        var value = definition.read(original);
        var rewrittenValue = definition.rewrite(value, context).value().orElseThrow();
        ClientboundDamageEventPacket rewritten = definition.rebuild(rewrittenValue);

        assertEquals(context.mapEntityId(100), rewritten.entityId());
        assertSame(registries.damageTypes().get(0), rewritten.sourceType());
        assertEquals(context.mapEntityId(200), rewritten.sourceCauseId());
        assertEquals(context.mapEntityId(300), rewritten.sourceDirectId());
        assertEquals(Optional.of(position), rewritten.sourcePosition());
        assertEquals(100, original.entityId());
        assertEquals(200, original.sourceCauseId());
        assertEquals(300, original.sourceDirectId());
        assertNotEquals(rewritten.entityId(), rewritten.sourceCauseId());
        assertNotEquals(rewritten.sourceCauseId(), rewritten.sourceDirectId());

        for (int cause : new int[] {-1, 51}) {
            for (int direct : new int[] {-1, 52}) {
                var optionalSources = definition.rewrite(
                        new Id025DamageEventPacketDefinition.Value(
                                50, registries.damageTypes().get(1), cause, direct, Optional.empty()),
                        rewriter()).value().orElseThrow();
                assertEquals(cause == -1 ? -1 : rewriter().mapEntityId(cause), optionalSources.sourceCauseId());
                assertEquals(direct == -1 ? -1 : rewriter().mapEntityId(direct), optionalSources.sourceDirectId());
            }
        }
    }

    @Test
    void mapsRepeatedIdsConsistentlyAndAcceptsNonSentinelNegativeIds() {
        var definition = new Id025DamageEventPacketDefinition();
        var context = rewriter();
        var rewritten = definition.rewrite(
                new Id025DamageEventPacketDefinition.Value(
                        -2, registries.damageTypes().get(2), -2, Integer.MAX_VALUE, Optional.empty()),
                context).value().orElseThrow();

        assertEquals(context.mapEntityId(-2), rewritten.entityId());
        assertEquals(rewritten.entityId(), rewritten.sourceCauseId());
        assertEquals(context.mapEntityId(Integer.MAX_VALUE), rewritten.sourceDirectId());
        assertTrue(rewritten.entityId() < 0);
        assertTrue(rewritten.sourceDirectId() < 0);
    }

    @Test
    void validatesRequiredValuesAndFiniteOptionalPosition() {
        var definition = new Id025DamageEventPacketDefinition();
        Holder<DamageType> type = registries.damageTypes().get(0);

        assertThrows(NullPointerException.class, () ->
                new Id025DamageEventPacketDefinition.Value(1, null, -1, -1, Optional.empty()));
        assertThrows(NullPointerException.class, () ->
                new Id025DamageEventPacketDefinition.Value(1, type, -1, -1, null));
        for (Vec3 invalid : new Vec3[] {
            new Vec3(Double.NaN, 0, 0),
            new Vec3(0, Double.POSITIVE_INFINITY, 0),
            new Vec3(0, 0, Double.NEGATIVE_INFINITY)
        }) {
            assertThrows(IncompatibleAdapterException.class, () ->
                    new Id025DamageEventPacketDefinition.Value(
                            1, type, -1, -1, Optional.of(invalid)));
        }
        assertThrows(IncompatibleAdapterException.class, () ->
                definition.rewrite(new Object(), descriptor(), rewriter()));
    }

    @Test
    void handlesPlaybackAndValidatedEncode() {
        var definition = new Id025DamageEventPacketDefinition();
        var context = rewriter();
        Vec3 position = new Vec3(1.25, -64.0, -0.0);
        ClientboundDamageEventPacket original = new ClientboundDamageEventPacket(
                41, registries.damageTypes().get(1), -1, 42, Optional.of(position));

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);
        ClientboundDamageEventPacket rewritten = assertInstanceOf(
                ClientboundDamageEventPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(context.mapEntityId(41), rewritten.entityId());
        assertEquals(-1, rewritten.sourceCauseId());
        assertEquals(context.mapEntityId(42), rewritten.sourceDirectId());
        assertEquals(Optional.of(position), rewritten.sourcePosition());
        assertEquals(25, definition.encode(rewritten, descriptor(), codec(original)).packetId());
    }

    @Test
    void nativeProtocolPreservesRegistryTypesEntitySentinelsAndOptionalPositions() {
        ProtocolInfo<?> protocol = protocol();
        int[] entityIds = {Integer.MIN_VALUE, -1, 0, 127, 128, 16_383, 16_384, Integer.MAX_VALUE};
        double[] coordinates = {
            -Double.MAX_VALUE,
            -1.25,
            -Double.MIN_VALUE,
            -0.0,
            0.0,
            Double.MIN_VALUE,
            1.25,
            Double.MAX_VALUE
        };

        for (Holder<DamageType> type : registries.damageTypes()) {
            for (int entityId : entityIds) {
                for (int cause : entityIds) {
                    for (int direct : entityIds) {
                        assertNativeRoundTrip(
                                protocol,
                                new ClientboundDamageEventPacket(
                                        entityId, type, cause, direct, Optional.empty()));
                    }
                }
            }
        }
        for (double x : coordinates) {
            assertNativeRoundTrip(
                    protocol,
                    new ClientboundDamageEventPacket(
                            7,
                            registries.damageTypes().get(0),
                            -1,
                            9,
                            Optional.of(new Vec3(x, -x, 42.5))));
        }
    }

    @Test
    void nativeProtocolRejectsUnknownDamageTypeAndIncompleteBodies() {
        ProtocolInfo<?> protocol = protocol();

        for (byte[] body : new byte[][] {
            new byte[0],
            new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80},
            new byte[] {0},
            new byte[] {0, (byte) 0x80},
            new byte[] {0, 0, (byte) 0x80},
            new byte[] {0, 0, 0, (byte) 0x80},
            new byte[] {0, 0, 0, 0},
            new byte[] {0, 0, 0, 0, 1},
            new byte[] {0, 3, 0, 0, 0}
        }) {
            ByteBuf encoded = Unpooled.buffer();
            try {
                writeVarInt(encoded, 25);
                encoded.writeBytes(body);
                assertThrows(RuntimeException.class, () -> decode(protocol, encoded));
            } finally {
                encoded.release();
            }
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundDamageEventPacket expected) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, expected);
            ByteBuf wireView = encoded.retainedDuplicate();
            try {
                assertEquals(25, readVarInt(wireView));
                assertEquals(expected.entityId(), readVarInt(wireView));
                assertEquals(
                        registries.damageRegistry().getId(expected.sourceType().value()),
                        readVarInt(wireView));
                assertEquals(expected.sourceCauseId() + 1, readVarInt(wireView));
                assertEquals(expected.sourceDirectId() + 1, readVarInt(wireView));
                assertEquals(expected.sourcePosition().isPresent(), wireView.readBoolean());
                if (expected.sourcePosition().isPresent()) {
                    Vec3 position = expected.sourcePosition().orElseThrow();
                    assertEquals(Double.doubleToRawLongBits(position.x()),
                            Double.doubleToRawLongBits(wireView.readDouble()));
                    assertEquals(Double.doubleToRawLongBits(position.y()),
                            Double.doubleToRawLongBits(wireView.readDouble()));
                    assertEquals(Double.doubleToRawLongBits(position.z()),
                            Double.doubleToRawLongBits(wireView.readDouble()));
                }
                assertFalse(wireView.isReadable());
            } finally {
                wireView.release();
            }

            ClientboundDamageEventPacket decoded = assertInstanceOf(
                    ClientboundDamageEventPacket.class, decode(protocol, encoded));
            assertEquals(expected.entityId(), decoded.entityId());
            assertSame(expected.sourceType(), decoded.sourceType());
            assertEquals(expected.sourceCauseId(), decoded.sourceCauseId());
            assertEquals(expected.sourceDirectId(), decoded.sourceDirectId());
            assertEquals(expected.sourcePosition(), decoded.sourcePosition());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static Holder<DamageType> registerDamageType(
            MappedRegistry<DamageType> registry, String name) {
        ResourceKey<DamageType> key = ResourceKey.create(
                Registries.DAMAGE_TYPE, Identifier.withDefaultNamespace(name));
        return registry.register(
                key,
                new DamageType(name, DamageScaling.NEVER, 0.0F),
                RegistrationInfo.BUILT_IN);
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registries.access()));
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
                25,
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 25, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundDamageEventPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundDamageEventPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        25,
                        new byte[] {25});
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

    private record TestRegistries(
            RegistryAccess.Frozen access,
            Registry<DamageType> damageRegistry,
            List<Holder<DamageType>> damageTypes) {}
}
