package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.entity;

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
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.Paper26_3PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_3.capture.Paper26_3CaptureTestState;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3ClientboundPacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
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
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.PositionStep;
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
    private static final PositionPath LINEAR = PositionPath.of(new Vec3(12.25, -64.0, 340.5));
    /** Paper 26.3 can sync a path of steps with tick offsets instead of one position. */
    private static final PositionPath STEPPED = PositionPath.stepped(java.util.List.of(
            new PositionStep(new Vec3(12.25, -64.0, 340.5), 0),
            new PositionStep(new Vec3(13.5, -63.0, 341.0), 1),
            new PositionStep(new Vec3(14.75, -62.5, 342.25), 3)));
    private static final float Y_ROT = 172.5F;
    private static final float X_ROT = -45.25F;

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
        var catalogMatches = Paper26_3ClientboundPacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id035EntityPositionSyncPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id035EntityPositionSyncPacketDefinition.class, catalogMatches.getFirst());

        var registryDescriptor = Paper26_3PacketRegistry.discover()
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
    void rewritesOnlyEntityIdAndPreservesPathRotationsAndGroundState() {
        var definition = new Id035EntityPositionSyncPacketDefinition();
        var context = rewriter();
        int mappedEntityId = context.mapEntityId(300);

        for (PositionPath path : new PositionPath[] {LINEAR, STEPPED}) {
            for (boolean onGround : new boolean[] {false, true}) {
                ClientboundEntityPositionSyncPacket original = packet(300, path, onGround);
                Id035EntityPositionSyncPacketDefinition.Value read = definition.read(original);
                assertEquals(300, read.entityId());
                assertEquals(path, read.path());
                assertEquals(Y_ROT, read.yRot());
                assertEquals(X_ROT, read.xRot());
                assertEquals(onGround, read.onGround());

                Id035EntityPositionSyncPacketDefinition.Value rewritten = definition
                        .rewrite(read, context)
                        .value()
                        .orElseThrow();
                assertEquals(mappedEntityId, rewritten.entityId());
                assertEquals(path, rewritten.path());
                assertEquals(onGround, rewritten.onGround());

                ClientboundEntityPositionSyncPacket rebuilt = definition.rebuild(rewritten);
                assertEquals(mappedEntityId, rebuilt.id());
                assertEquals(path, rebuilt.position());
                assertEquals(Y_ROT, rebuilt.yRot());
                assertEquals(X_ROT, rebuilt.xRot());
                assertEquals(onGround, rebuilt.onGround());
            }
        }

        assertNotEquals(300, mappedEntityId);
    }

    @Test
    void acceptsLargeFiniteCoordinatesAndUnnormalizedFiniteRotations() {
        PositionPath edge = PositionPath.of(new Vec3(Double.MAX_VALUE, -Double.MAX_VALUE, 0.0));

        var value = new Id035EntityPositionSyncPacketDefinition.Value(5, edge, 720.5F, -1080.25F, true);
        assertEquals(edge, value.path());
        assertEquals(720.5F, value.yRot());
    }

    @Test
    void rejectsNonFinitePositionAndRotationValues() {
        var definition = new Id035EntityPositionSyncPacketDefinition();
        Vec3 finite = LINEAR.endPosition();

        for (double invalid : new double[] {
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        }) {
            assertInvalid(definition, PositionPath.of(new Vec3(invalid, finite.y, finite.z)), Y_ROT, X_ROT);
            assertInvalid(definition, PositionPath.of(new Vec3(finite.x, invalid, finite.z)), Y_ROT, X_ROT);
            assertInvalid(definition, PositionPath.of(new Vec3(finite.x, finite.y, invalid)), Y_ROT, X_ROT);
        }

        assertInvalid(definition, LINEAR, Float.NaN, X_ROT);
        assertInvalid(definition, LINEAR, Y_ROT, Float.POSITIVE_INFINITY);
        assertThrows(
                NullPointerException.class,
                () -> new Id035EntityPositionSyncPacketDefinition.Value(1, null, 0.0F, 0.0F, true));
    }

    @Test
    void captureContextPlacesTheEntityAtTheEndOfItsPath() {
        var definition = new Id035EntityPositionSyncPacketDefinition();
        var captureState = Paper26_3CaptureTestState.create();
        UUID connectionId = UUID.randomUUID();
        captureState.world(connectionId, "minecraft:overworld");

        definition.captureContext(packet(300, STEPPED, false), descriptor(), connectionId, captureState, 4L);

        var position = captureState.entityPosition(connectionId, 300).orElseThrow();
        assertEquals(14.75, position.x());
        assertEquals(-62.5, position.y());
        assertEquals(342.25, position.z());
    }

    @Test
    void playbackRewritesAndEncodesTheRebuiltState() {
        var definition = new Id035EntityPositionSyncPacketDefinition();
        var context = rewriter();
        ClientboundEntityPositionSyncPacket original = packet(401, STEPPED, true);
        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        ClientboundEntityPositionSyncPacket rewritten = assertInstanceOf(
                ClientboundEntityPositionSyncPacket.class, result.packet().orElseThrow());
        assertEquals(context.mapEntityId(401), rewritten.id());
        assertEquals(STEPPED, rewritten.position());
        assertTrue(rewritten.onGround());
        assertEquals(PACKET_ID, definition.encode(rewritten, descriptor(), codec(rewritten)).packetId());
    }

    @Test
    void validatesDescriptorBeforePlaybackAndEncode() {
        var definition = new Id035EntityPositionSyncPacketDefinition();
        ClientboundEntityPositionSyncPacket original = packet(402, LINEAR, false);

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
        for (PositionPath path : new PositionPath[] {LINEAR, STEPPED})
        for (boolean onGround : new boolean[] {false, true}) {
            ClientboundEntityPositionSyncPacket expected = packet(300, path, onGround);
            ByteBuf encoded = encodePacket(protocol, expected);
            try {
                ByteBuf wire = encoded.duplicate();
                assertEquals(PACKET_ID, readVarInt(wire));
                assertEquals(300, readVarInt(wire));
                // Paper 26.3: the position path, then the rotations; no delta movement.
                assertEquals(path, PositionPath.STREAM_CODEC.decode(wire));
                assertEquals(Y_ROT, wire.readFloat());
                assertEquals(X_ROT, wire.readFloat());
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

        ByteBuf complete = encodePacket(protocol, packet(300, STEPPED, true));
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
            PositionPath path,
            float yRot,
            float xRot) {
        assertThrows(
                IncompatibleAdapterException.class,
                () -> definition.read(new ClientboundEntityPositionSyncPacket(1, path, yRot, xRot, false)));
    }

    private static ClientboundEntityPositionSyncPacket packet(
            int entityId, PositionPath path, boolean onGround) {
        return new ClientboundEntityPositionSyncPacket(entityId, path, Y_ROT, X_ROT, onGround);
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

    private static Paper26_3ReplayPacketRewriter rewriter() {
        return new Paper26_3ReplayPacketRewriter(
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
                "paper-26.3/" + TYPE_NAME,
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
