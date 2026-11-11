package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id001AddEntityPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:add_entity";
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RECORDED_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesSpawnAsCheckpointRelevantIdentityBearingState() {
        var definition = new Id001AddEntityPacketDefinition();

        assertEquals(1, definition.packetId());
        assertEquals(0x01, Id001AddEntityPacketDefinition.PACKET_ID);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
    }

    @Test
    void rewritesIdentityAndPreservesEverySceneField() {
        var definition = new Id001AddEntityPacketDefinition();
        var context = rewriter();
        EntityType<?> type = entityType("minecraft:armor_stand");
        Vec3 movement = new Vec3(0.125D, -0.25D, 0.5D);
        ClientboundAddEntityPacket original = packet(41, RECORDED_ID, type, 73, movement);

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);
        ClientboundAddEntityPacket rewritten = assertInstanceOf(
                ClientboundAddEntityPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(context.mapEntityId(41), rewritten.getId());
        assertEquals(context.mapUuid(RECORDED_ID), rewritten.getUUID());
        assertNotEquals(41, rewritten.getId());
        assertNotEquals(RECORDED_ID, rewritten.getUUID());
        assertSame(type, rewritten.getType());
        assertEquals(original.getX(), rewritten.getX());
        assertEquals(original.getY(), rewritten.getY());
        assertEquals(original.getZ(), rewritten.getZ());
        assertEquals(original.getMovement(), rewritten.getMovement());
        assertEquals(original.getXRot(), rewritten.getXRot());
        assertEquals(original.getYRot(), rewritten.getYRot());
        assertEquals(original.getYHeadRot(), rewritten.getYHeadRot());
        assertEquals(73, rewritten.getData());

        PacketCodec.EncodedPacket encoded = definition.encode(
                rewritten, descriptor(), codec(original));
        assertEquals(PacketPhase.PLAY, encoded.phase());
        assertEquals(PacketDescriptor.Direction.CLIENTBOUND, encoded.direction());
        assertEquals(1, encoded.packetId());
    }

    @Test
    void rewritesEveryProjectileOwnerReferenceIncludingFishingHooks() {
        var definition = new Id001AddEntityPacketDefinition();
        var context = rewriter();

        for (String typeName : Set.of(
                "minecraft:arrow",
                "minecraft:breeze_wind_charge",
                "minecraft:dragon_fireball",
                "minecraft:egg",
                "minecraft:ender_pearl",
                "minecraft:experience_bottle",
                "minecraft:fireball",
                "minecraft:firework_rocket",
                "minecraft:fishing_bobber",
                "minecraft:lingering_potion",
                "minecraft:llama_spit",
                "minecraft:shulker_bullet",
                "minecraft:small_fireball",
                "minecraft:snowball",
                "minecraft:spectral_arrow",
                "minecraft:splash_potion",
                "minecraft:trident",
                "minecraft:wind_charge",
                "minecraft:wither_skull")) {
            ClientboundAddEntityPacket original = packet(
                    50, RECORDED_ID, entityType(typeName), 21, Vec3.ZERO);
            Id001AddEntityPacketDefinition.Value value = definition.read(original);
            Id001AddEntityPacketDefinition.Value rewritten = definition
                    .rewrite(value, context)
                    .value()
                    .orElseThrow();

            assertEquals(context.mapEntityId(21), rewritten.data(), typeName);
        }
    }

    @Test
    void preservesZeroAndEveryNonIdentityDataSemantic() {
        var definition = new Id001AddEntityPacketDefinition();
        var context = rewriter();
        context.mapEntityId(37);

        for (TestValue test : Set.of(
                new TestValue("minecraft:snowball", 0),
                new TestValue(
                        "minecraft:falling_block",
                        Block.getId(Blocks.SAND.defaultBlockState())),
                new TestValue("minecraft:item_frame", 0),
                new TestValue("minecraft:item_frame", 5),
                new TestValue("minecraft:glow_item_frame", 1),
                new TestValue("minecraft:painting", 2),
                new TestValue("minecraft:painting", 5),
                new TestValue("minecraft:warden", 0),
                new TestValue("minecraft:warden", 1),
                new TestValue("minecraft:warden", 7),
                new TestValue("minecraft:armor_stand", 91))) {
            Id001AddEntityPacketDefinition.Value value = definition.read(packet(
                    51, RECORDED_ID, entityType(test.typeName()), test.data(), Vec3.ZERO));
            Id001AddEntityPacketDefinition.Value rewritten = definition
                    .rewrite(value, context)
                    .value()
                    .orElseThrow();

            assertEquals(test.data(), rewritten.data(), test.typeName());
        }
    }

    @Test
    void preservesFishingHookSelfReferenceAfterIdentityRewrite() {
        var definition = new Id001AddEntityPacketDefinition();
        var context = rewriter();
        Id001AddEntityPacketDefinition.Value value = definition.read(packet(
                52,
                RECORDED_ID,
                entityType("minecraft:fishing_bobber"),
                52,
                Vec3.ZERO));

        Id001AddEntityPacketDefinition.Value rewritten = definition
                .rewrite(value, context)
                .value()
                .orElseThrow();

        assertEquals(context.mapEntityId(52), rewritten.entityId());
        assertEquals(rewritten.entityId(), rewritten.data());
    }

    @Test
    void rejectsNonFinitePositionAndMovement() {
        EntityType<?> type = entityType("minecraft:armor_stand");

        assertThrows(IncompatibleAdapterException.class, () ->
                new Id001AddEntityPacketDefinition.Value(
                        1, RECORDED_ID, type, Double.NaN, 0.0D, 0.0D,
                        Vec3.ZERO, 0.0F, 0.0F, 0.0F, 0));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id001AddEntityPacketDefinition.Value(
                        1, RECORDED_ID, type, 0.0D, 0.0D, 0.0D,
                        new Vec3(0.0D, Double.POSITIVE_INFINITY, 0.0D),
                        0.0F, 0.0F, 0.0F, 0));
    }

    @Test
    void nativeProtocolUsesIdOneAndConsumesTheCompleteBody() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        ProtocolInfo<?> protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
        ClientboundAddEntityPacket original = packet(
                61,
                RECORDED_ID,
                entityType("minecraft:armor_stand"),
                12,
                new Vec3(0.125D, -0.25D, 0.5D));
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, original);
            ByteBuf packetIdView = encoded.duplicate();
            assertEquals(1, readVarInt(packetIdView));

            Packet<?> decoded = decode(protocol, encoded);
            ClientboundAddEntityPacket addEntity = assertInstanceOf(
                    ClientboundAddEntityPacket.class, decoded);
            assertFalse(encoded.isReadable());
            assertEquals(original.getId(), addEntity.getId());
            assertEquals(original.getUUID(), addEntity.getUUID());
            assertSame(original.getType(), addEntity.getType());
            assertEquals(original.getX(), addEntity.getX());
            assertEquals(original.getY(), addEntity.getY());
            assertEquals(original.getZ(), addEntity.getZ());
            assertEquals(original.getMovement().x, addEntity.getMovement().x, 0.0001D);
            assertEquals(original.getMovement().y, addEntity.getMovement().y, 0.0001D);
            assertEquals(original.getMovement().z, addEntity.getMovement().z, 0.0001D);
            assertEquals(original.getXRot(), addEntity.getXRot());
            assertEquals(original.getYRot(), addEntity.getYRot());
            assertEquals(original.getYHeadRot(), addEntity.getYHeadRot());
            assertEquals(original.getData(), addEntity.getData());
        } finally {
            encoded.release();
        }
    }

    private static ClientboundAddEntityPacket packet(
            int entityId,
            UUID uuid,
            EntityType<?> type,
            int data,
            Vec3 movement) {
        return new ClientboundAddEntityPacket(
                entityId,
                uuid,
                12.25D,
                64.5D,
                -31.75D,
                22.5F,
                -90.0F,
                type,
                data,
                movement,
                135.0D);
    }

    private static EntityType<?> entityType(String identifier) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.parse(identifier));
        if (type == null) {
            throw new AssertionError("missing test entity type " + identifier);
        }
        return type;
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
                1,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 1, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundAddEntityPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundAddEntityPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        1,
                        new byte[] {2});
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

    private record TestValue(String typeName, int data) {}
}
