package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id125TeleportEntityPacketDefinitionTest {

    private static final int PACKET_ID = 125;
    private static final String TYPE_NAME = "play.clientbound.minecraft:teleport_entity";
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
    void classifiesTeleportAsCheckpointRelevantIdentityBearingState() {
        var definition = new Id125TeleportEntityPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x7D, Id125TeleportEntityPacketDefinition.PACKET_ID);
        assertEquals("minecraft:teleport_entity", Id125TeleportEntityPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
        assertEquals(Paper26_2CheckpointEncoder.CheckpointPacketFamily.ENTITY_METADATA,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));
    }

    @Test
    void registersExactlyOnceInEntityCatalogAndPaperRegistry() {
        var domainMatches = EntityPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id125TeleportEntityPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id125TeleportEntityPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void rewritesOnlyTheEntityIdAndPreservesAllMovementSemantics() {
        var definition = new Id125TeleportEntityPacketDefinition();
        var context = rewriter();
        PositionMoveRotation change = new PositionMoveRotation(
                new Vec3(12.125D, -64.5D, 900.75D),
                new Vec3(-0.125D, 0.0D, 0.625D),
                91.5F,
                -42.25F);
        Set<Relative> relatives = Set.of(
                Relative.X,
                Relative.Y_ROT,
                Relative.DELTA_Z,
                Relative.ROTATE_DELTA);
        ClientboundTeleportEntityPacket original = packet(41, change, relatives, true);

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);
        ClientboundTeleportEntityPacket rewritten = assertInstanceOf(
                ClientboundTeleportEntityPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(context.mapEntityId(41), rewritten.id());
        assertNotEquals(41, rewritten.id());
        assertEquals(change, rewritten.change());
        assertEquals(relatives, rewritten.relatives());
        assertTrue(rewritten.onGround());
        assertEquals(PACKET_ID, definition.encode(rewritten, descriptor(), codec(original)).packetId());
    }

    @Test
    void mapsEveryEntityIdIncludingValuesWithNoPacketSentinelMeaning() {
        var definition = new Id125TeleportEntityPacketDefinition();
        var context = rewriter();
        PositionMoveRotation change = change();

        for (int recordedId : new int[] {
            Integer.MIN_VALUE, -1, 0, 1, 127, 128, Integer.MAX_VALUE
        }) {
            Id125TeleportEntityPacketDefinition.Value rewritten = definition
                    .rewrite(new Id125TeleportEntityPacketDefinition.Value(
                            recordedId, change, Set.of(), false), context)
                    .value()
                    .orElseThrow();
            assertEquals(context.mapEntityId(recordedId), rewritten.entityId());
            assertNotEquals(recordedId, rewritten.entityId());
        }
    }

    @Test
    void preservesAll512RelativeFlagCombinationsThroughTheNativeProtocol() {
        ProtocolInfo<?> protocol = protocol();
        Relative[] possibleFlags = Relative.values();
        assertEquals(9, possibleFlags.length);

        for (int mask = 0; mask < (1 << possibleFlags.length); mask++) {
            EnumSet<Relative> relatives = EnumSet.noneOf(Relative.class);
            for (int bit = 0; bit < possibleFlags.length; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    relatives.add(possibleFlags[bit]);
                }
            }

            PositionMoveRotation change = change();
            boolean onGround = (mask & 1) != 0;
            ClientboundTeleportEntityPacket original = packet(73, change, relatives, onGround);
            ByteBuf encoded = Unpooled.buffer();
            try {
                encode(protocol, encoded, original);
                ByteBuf packetIdView = encoded.duplicate();
                assertEquals(PACKET_ID, readVarInt(packetIdView));

                ClientboundTeleportEntityPacket decoded = assertInstanceOf(
                        ClientboundTeleportEntityPacket.class, decode(protocol, encoded));
                assertFalse(encoded.isReadable());
                assertEquals(original.id(), decoded.id(), "mask " + mask);
                assertEquals(change, decoded.change(), "mask " + mask);
                assertEquals(relatives, decoded.relatives(), "mask " + mask);
                assertEquals(onGround, decoded.onGround(), "mask " + mask);
            } finally {
                encoded.release();
            }
        }
    }

    @Test
    void nativeProtocolPreservesFiniteNumericEdgeValuesAndRejectsTruncation() {
        ProtocolInfo<?> protocol = protocol();
        PositionMoveRotation edgeValues = new PositionMoveRotation(
                new Vec3(-0.0D, Double.MIN_VALUE, Double.MAX_VALUE),
                new Vec3(-Double.MAX_VALUE, 1.0D, -0.0D),
                -0.0F,
                Float.MAX_VALUE);
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, packet(91, edgeValues, Relative.ALL, false));
            ClientboundTeleportEntityPacket decoded = assertInstanceOf(
                    ClientboundTeleportEntityPacket.class, decode(protocol, encoded));

            assertEquals(Double.doubleToRawLongBits(-0.0D),
                    Double.doubleToRawLongBits(decoded.change().position().x));
            assertEquals(Double.doubleToRawLongBits(Double.MIN_VALUE),
                    Double.doubleToRawLongBits(decoded.change().position().y));
            assertEquals(Double.doubleToRawLongBits(Double.MAX_VALUE),
                    Double.doubleToRawLongBits(decoded.change().position().z));
            assertEquals(Float.floatToRawIntBits(-0.0F),
                    Float.floatToRawIntBits(decoded.change().yRot()));
            assertEquals(Float.floatToRawIntBits(Float.MAX_VALUE),
                    Float.floatToRawIntBits(decoded.change().xRot()));
        } finally {
            encoded.release();
        }

        for (byte[] payload : new byte[][] {
            {(byte) PACKET_ID},
            {(byte) PACKET_ID, 1},
            {(byte) PACKET_ID, 1, 0},
            {(byte) PACKET_ID, 1, 0, 0x7F}
        }) {
            ByteBuf truncated = Unpooled.wrappedBuffer(payload);
            try {
                assertThrows(RuntimeException.class, () -> decode(protocol, truncated));
            } finally {
                truncated.release();
            }
        }
    }

    @Test
    void rejectsNonFinitePositionMotionAndRotationValues() {
        assertThrows(IncompatibleAdapterException.class, () -> value(
                new PositionMoveRotation(
                        new Vec3(Double.NaN, 0.0D, 0.0D), Vec3.ZERO, 0.0F, 0.0F)));
        assertThrows(IncompatibleAdapterException.class, () -> value(
                new PositionMoveRotation(
                        Vec3.ZERO,
                        new Vec3(0.0D, Double.POSITIVE_INFINITY, 0.0D),
                        0.0F,
                        0.0F)));
        assertThrows(IncompatibleAdapterException.class, () -> value(
                new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, Float.NaN, 0.0F)));
        assertThrows(IncompatibleAdapterException.class, () -> value(
                new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0.0F, Float.NEGATIVE_INFINITY)));
    }

    private static Id125TeleportEntityPacketDefinition.Value value(PositionMoveRotation change) {
        return new Id125TeleportEntityPacketDefinition.Value(1, change, Set.of(), false);
    }

    private static PositionMoveRotation change() {
        return new PositionMoveRotation(
                new Vec3(12.125D, -64.5D, 900.75D),
                new Vec3(-0.125D, 0.0D, 0.625D),
                91.5F,
                -42.25F);
    }

    private static ClientboundTeleportEntityPacket packet(
            int entityId,
            PositionMoveRotation change,
            Set<Relative> relatives,
            boolean onGround) {
        return new ClientboundTeleportEntityPacket(entityId, change, relatives, onGround);
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
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
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundTeleportEntityPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundTeleportEntityPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        PACKET_ID,
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
}
