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
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id035EntityPositionSyncPacketDefinitionTest {

    private static final int PACKET_ID = 35;
    private static final String TYPE_NAME = "play.clientbound.minecraft:entity_position_sync";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final PositionMoveRotation VALUES = new PositionMoveRotation(
            new Vec3(12.25, -64.0, 340.5),
            new Vec3(-1.25, 0.0, 0.5),
            172.5F,
            -45.25F);

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesPositionSyncAsStatefulCheckpointRelevantSceneData() {
        var definition = new Id035EntityPositionSyncPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x23, Id035EntityPositionSyncPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:entity_position_sync",
                Id035EntityPositionSyncPacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInEntityDomainCatalogAndPaperRegistry() {
        var domainMatches = EntityPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id035EntityPositionSyncPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id035EntityPositionSyncPacketDefinition.class, catalogMatches.getFirst());

        var registryDescriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, registryDescriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, registryDescriptor.disposition());
        assertTrue(registryDescriptor.captureByDefault());
        assertTrue(registryDescriptor.replayable());
        assertTrue(registryDescriptor.checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                registryDescriptor.playbackScope());
    }

    @Test
    void rewritesOnlyEntityIdAndPreservesAllTransformAndGroundFields() {
        var definition = new Id035EntityPositionSyncPacketDefinition();
        var context = rewriter();
        int mappedEntityId = context.mapEntityId(300);

        for (boolean onGround : new boolean[] {false, true}) {
            ClientboundEntityPositionSyncPacket original = packet(300, VALUES, onGround);
            Id035EntityPositionSyncPacketDefinition.Value read = definition.read(original);
            assertEquals(300, read.entityId());
            assertEquals(VALUES, read.values());
            assertEquals(onGround, read.onGround());

            Id035EntityPositionSyncPacketDefinition.Value rewritten = definition
                    .rewrite(read, context)
                    .value()
                    .orElseThrow();
            assertEquals(mappedEntityId, rewritten.entityId());
            assertEquals(VALUES, rewritten.values());
            assertEquals(onGround, rewritten.onGround());

            ClientboundEntityPositionSyncPacket rebuilt = definition.rebuild(rewritten);
            assertEquals(mappedEntityId, rebuilt.id());
            assertEquals(VALUES, rebuilt.values());
            assertEquals(onGround, rebuilt.onGround());
        }

        assertNotEquals(300, mappedEntityId);
    }

    @Test
    void acceptsLargeFiniteCoordinatesAndUnnormalizedFiniteRotations() {
        PositionMoveRotation edgeValues = new PositionMoveRotation(
                new Vec3(Double.MAX_VALUE, -Double.MAX_VALUE, 0.0),
                new Vec3(Double.MAX_VALUE, 0.0, -Double.MAX_VALUE),
                720.5F,
                -1080.25F);

        assertEquals(
                edgeValues,
                new Id035EntityPositionSyncPacketDefinition.Value(5, edgeValues, true).values());
    }

    @Test
    void rejectsNonFinitePositionMovementAndRotationValues() {
        var definition = new Id035EntityPositionSyncPacketDefinition();
        PositionMoveRotation finite = VALUES;

        for (double invalid : new double[] {
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        }) {
            assertInvalid(definition, new PositionMoveRotation(
                    new Vec3(invalid, finite.position().y, finite.position().z),
                    finite.deltaMovement(), finite.yRot(), finite.xRot()));
            assertInvalid(definition, new PositionMoveRotation(
                    new Vec3(finite.position().x, invalid, finite.position().z),
                    finite.deltaMovement(), finite.yRot(), finite.xRot()));
            assertInvalid(definition, new PositionMoveRotation(
                    new Vec3(finite.position().x, finite.position().y, invalid),
                    finite.deltaMovement(), finite.yRot(), finite.xRot()));
            assertInvalid(definition, new PositionMoveRotation(
                    finite.position(),
                    new Vec3(invalid, finite.deltaMovement().y, finite.deltaMovement().z),
                    finite.yRot(), finite.xRot()));
            assertInvalid(definition, new PositionMoveRotation(
                    finite.position(),
                    new Vec3(finite.deltaMovement().x, invalid, finite.deltaMovement().z),
                    finite.yRot(), finite.xRot()));
            assertInvalid(definition, new PositionMoveRotation(
                    finite.position(),
                    new Vec3(finite.deltaMovement().x, finite.deltaMovement().y, invalid),
                    finite.yRot(), finite.xRot()));
        }

        assertInvalid(definition, new PositionMoveRotation(
                finite.position(), finite.deltaMovement(), Float.NaN, finite.xRot()));
        assertInvalid(definition, new PositionMoveRotation(
                finite.position(), finite.deltaMovement(), finite.yRot(), Float.POSITIVE_INFINITY));
        assertThrows(
                NullPointerException.class,
                () -> new Id035EntityPositionSyncPacketDefinition.Value(1, null, true));
        assertThrows(
                NullPointerException.class,
                () -> new Id035EntityPositionSyncPacketDefinition.Value(
                        1,
                        new PositionMoveRotation(null, finite.deltaMovement(), 0.0F, 0.0F),
                        true));
    }

    @Test
    void playbackRewritesAndEncodesTheRebuiltState() {
        var definition = new Id035EntityPositionSyncPacketDefinition();
        var context = rewriter();
        ClientboundEntityPositionSyncPacket original = packet(401, VALUES, true);
        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        ClientboundEntityPositionSyncPacket rewritten = assertInstanceOf(
                ClientboundEntityPositionSyncPacket.class, result.packet().orElseThrow());
        assertEquals(context.mapEntityId(401), rewritten.id());
        assertEquals(VALUES, rewritten.values());
        assertTrue(rewritten.onGround());
        assertEquals(PACKET_ID, definition.encode(rewritten, descriptor(), codec(rewritten)).packetId());
    }

    @Test
    void validatesDescriptorBeforePlaybackAndEncode() {
        var definition = new Id035EntityPositionSyncPacketDefinition();
        ClientboundEntityPositionSyncPacket original = packet(402, VALUES, false);

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec(original), rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(), descriptor(PACKET_ID + 1, true, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                codec(original), rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(), descriptor(PACKET_ID, true, PacketDescriptor.PlaybackScope.SAFE),
                codec(original), rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.encode(
                original,
                descriptor(PACKET_ID, false, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                codec(original)));
    }

    @Test
    void nativeProtocolUsesIdThirtyFiveAndEncodesEveryFieldInOrder() {
        ProtocolInfo<?> protocol = protocol();
        for (boolean onGround : new boolean[] {false, true}) {
            ClientboundEntityPositionSyncPacket expected = packet(300, VALUES, onGround);
            ByteBuf encoded = encodePacket(protocol, expected);
            try {
                ByteBuf wire = encoded.duplicate();
                assertEquals(PACKET_ID, readVarInt(wire));
                assertEquals(300, readVarInt(wire));
                assertEquals(VALUES.position().x, wire.readDouble());
                assertEquals(VALUES.position().y, wire.readDouble());
                assertEquals(VALUES.position().z, wire.readDouble());
                assertEquals(VALUES.deltaMovement().x, wire.readDouble());
                assertEquals(VALUES.deltaMovement().y, wire.readDouble());
                assertEquals(VALUES.deltaMovement().z, wire.readDouble());
                assertEquals(VALUES.yRot(), wire.readFloat());
                assertEquals(VALUES.xRot(), wire.readFloat());
                assertEquals(onGround, wire.readBoolean());
                assertFalse(wire.isReadable());

                ClientboundEntityPositionSyncPacket decoded = assertInstanceOf(
                        ClientboundEntityPositionSyncPacket.class, decode(protocol, encoded));
                assertEquals(expected, decoded);
                assertFalse(encoded.isReadable());
            } finally {
                encoded.release();
            }
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPacketBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, new byte[0]);

        ByteBuf complete = encodePacket(protocol, packet(300, VALUES, true));
        try {
            ByteBuf wire = complete.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            byte[] payload = new byte[wire.readableBytes()];
            wire.readBytes(payload);
            for (int length = 0; length < payload.length; length++) {
                assertMalformed(protocol, java.util.Arrays.copyOf(payload, length));
            }
        } finally {
            complete.release();
        }
    }

    private static void assertInvalid(
            Id035EntityPositionSyncPacketDefinition definition,
            PositionMoveRotation values) {
        assertThrows(
                IncompatibleAdapterException.class,
                () -> definition.read(packet(1, values, false)));
    }

    private static ClientboundEntityPositionSyncPacket packet(
            int entityId, PositionMoveRotation values, boolean onGround) {
        return new ClientboundEntityPositionSyncPacket(entityId, values, onGround);
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

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(
                PACKET_ID,
                true,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static PacketDescriptor descriptor(
            int packetId,
            boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                replayable,
                true,
                "paper-26.2/" + TYPE_NAME,
                scope);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(
                0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundEntityPositionSyncPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundEntityPositionSyncPacket.class, packet);
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
