package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2CheckpointEncoder;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.LpVec3;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id101SetEntityMotionPacketDefinitionTest {

    private static final int PACKET_ID = 101;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_entity_motion";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000111");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000112");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesEntityMotionAsCheckpointRelevantIdentityBearingState() {
        var definition = new Id101SetEntityMotionPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x65, Id101SetEntityMotionPacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_entity_motion", Id101SetEntityMotionPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
        assertEquals(Paper26_2CheckpointEncoder.CheckpointPacketFamily.ENTITY_METADATA,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));

        var domainMatches = EntityPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id101SetEntityMotionPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id101SetEntityMotionPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                descriptor.playbackScope());
    }

    @Test
    void nativeCodecUsesPacketIdEntityVarIntAndCompactZeroVector() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundSetEntityMotionPacket expected = packet(42, Vec3.ZERO);
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] expectedWire = bytes(encoded);
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(42, fields.readVarInt());
            assertEquals(1, fields.readableBytes());
            assertEquals(0, fields.readUnsignedByte());
            assertFalse(fields.isReadable());

            ClientboundSetEntityMotionPacket decoded = assertInstanceOf(
                    ClientboundSetEntityMotionPacket.class, decode(protocol, encoded));
            assertEquals(42, decoded.id());
            assertEquals(Vec3.ZERO, decoded.movement());
            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(expectedWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeCodecPreservesCompactAndExtendedLpv3Ranges() {
        ProtocolInfo<?> protocol = protocol();
        Vec3 compact = new Vec3(0.25D, -0.5D, 2.0D);
        Vec3 extended = new Vec3(10.0D, -5.5D, 2.0D);

        ByteBuf compactEncoding = encodeVector(compact);
        ByteBuf extendedEncoding = encodeVector(extended);
        try {
            assertEquals(6, compactEncoding.readableBytes());
            assertFalse(LpVec3.hasContinuationBit(compactEncoding.getUnsignedByte(0)));
            assertTrue(extendedEncoding.readableBytes() > compactEncoding.readableBytes());
            assertTrue(LpVec3.hasContinuationBit(extendedEncoding.getUnsignedByte(0)));
        } finally {
            compactEncoding.release();
            extendedEncoding.release();
        }

        assertNativeRoundTrip(protocol, packet(Integer.MIN_VALUE, compact));
        assertNativeRoundTrip(protocol, packet(Integer.MAX_VALUE, extended));
    }

    @Test
    void nativeCodecQuantizesSubMinimumVectorsAndSanitizesNonFiniteComponents() {
        ProtocolInfo<?> protocol = protocol();
        double belowMinimum = 1.0E-7D;

        ClientboundSetEntityMotionPacket tiny = roundTrip(
                protocol, packet(7, new Vec3(belowMinimum, -belowMinimum, belowMinimum)));
        assertEquals(Vec3.ZERO, tiny.movement());

        ClientboundSetEntityMotionPacket nonFinite = roundTrip(
                protocol,
                packet(8, new Vec3(
                        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)));
        assertEquals(0.0D, nonFinite.movement().x);
        assertTrue(Double.isFinite(nonFinite.movement().y));
        assertTrue(Double.isFinite(nonFinite.movement().z));
        assertTrue(Math.abs(nonFinite.movement().y) <= LpVec3.ABS_MAX_VALUE);
        assertTrue(Math.abs(nonFinite.movement().z) <= LpVec3.ABS_MAX_VALUE);
    }

    @Test
    void rewriteMapsTheEntityIdAndPreservesEveryMotionComponent() {
        var definition = new Id101SetEntityMotionPacketDefinition();
        var context = rewriter();
        int recordedEntityId = 1501;
        Vec3 movement = new Vec3(-0.125D, 1.75D, -5.25D);

        var rewritten = definition.rewrite(
                new Id101SetEntityMotionPacketDefinition.Value(recordedEntityId, movement),
                context).value().orElseThrow();

        assertEquals(context.mapEntityId(recordedEntityId), rewritten.entityId());
        assertNotEquals(recordedEntityId, rewritten.entityId());
        assertSame(movement, rewritten.movement());
    }

    @Test
    void playbackRewritesEntityIdentityAndEncodesTheNativeMotionVector() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id101SetEntityMotionPacketDefinition();
        var context = rewriter();
        PacketCodec<Object> codec = codec(protocol);
        int recordedEntityId = 1601;
        Vec3 movement = new Vec3(-2.25D, 0.875D, 4.5D);
        Vec3 encodedMovement = roundTripVector(movement);

        PacketRewriteResult result = definition.handlePlayback(
                frame(body(protocol, packet(recordedEntityId, movement))),
                descriptor(),
                codec,
                context);
        ClientboundSetEntityMotionPacket rebuilt = assertInstanceOf(
                ClientboundSetEntityMotionPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(context.mapEntityId(recordedEntityId), rebuilt.id());
        assertEquals(encodedMovement, rebuilt.movement());

        PacketCodec.EncodedPacket encoded = definition.encode(rebuilt, descriptor(), codec);
        assertEquals(PACKET_ID, encoded.packetId());
        ClientboundSetEntityMotionPacket decoded = assertInstanceOf(
                ClientboundSetEntityMotionPacket.class,
                decodePayload(protocol, encoded.payload()));
        assertEquals(rebuilt.id(), decoded.id());
        assertEquals(rebuilt.movement(), decoded.movement());
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedEntityAndExtendedVectorPrefix() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completeBody = body(protocol, packet(23, new Vec3(10.0D, -8.0D, 3.0D)));

        for (int length = 0; length < completeBody.length; length++) {
            assertNativeDecodeFails(protocol, java.util.Arrays.copyOf(completeBody, length));
        }
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            VarInt.write(wire, 24);
            Vec3.LP_STREAM_CODEC.encode(wire, new Vec3(1.0D, -1.5D, 2.0D));
            wire.writeByte(0x7A);

            ClientboundSetEntityMotionPacket decoded = assertInstanceOf(
                    ClientboundSetEntityMotionPacket.class, decode(protocol, wire));

            assertEquals(24, decoded.id());
            assertEquals(roundTripVector(new Vec3(1.0D, -1.5D, 2.0D)), decoded.movement());
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void validatesDescriptorBeforeDecodingOrEncoding() {
        var definition = new Id101SetEntityMotionPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes);
        var packet = packet(2, Vec3.ZERO);

        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(TYPE_NAME, PACKET_ID + 1, true), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor("play.clientbound.minecraft:other", PACKET_ID, true),
                codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(TYPE_NAME, PACKET_ID, false), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.encode(
                packet, descriptor(TYPE_NAME, PACKET_ID + 1, true), codec));
        assertEquals(0, decodes.get());
    }

    private static ClientboundSetEntityMotionPacket roundTrip(
            ProtocolInfo<?> protocol, ClientboundSetEntityMotionPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            return assertInstanceOf(ClientboundSetEntityMotionPacket.class, decode(protocol, encoded));
        } finally {
            encoded.release();
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundSetEntityMotionPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            byte[] expectedWire = bytes(encoded);
            ClientboundSetEntityMotionPacket decoded = assertInstanceOf(
                    ClientboundSetEntityMotionPacket.class, decode(protocol, encoded));
            assertEquals(packet.id(), decoded.id());

            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(expectedWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static ClientboundSetEntityMotionPacket packet(int entityId, Vec3 movement) {
        return new ClientboundSetEntityMotionPacket(entityId, movement);
    }

    private static ByteBuf encodeVector(Vec3 vector) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            Vec3.LP_STREAM_CODEC.encode(buffer, vector);
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.release();
            throw failure;
        }
    }

    private static Vec3 roundTripVector(Vec3 vector) {
        ByteBuf buffer = encodeVector(vector);
        try {
            return Vec3.LP_STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static byte[] body(ProtocolInfo<?> protocol, ClientboundSetEntityMotionPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            return bytes(fields);
        } finally {
            encoded.release();
        }
    }

    private static Object decodePayload(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(payload);
            return decode(protocol, wire);
        } finally {
            wire.release();
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

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static void assertNativeDecodeFails(ProtocolInfo<?> protocol, byte[] body) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(body);
            assertThrows(RuntimeException.class, () -> decode(protocol, wire));
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
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> codec(ProtocolInfo<?> protocol) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                ClientboundSetEntityMotionPacket nativePacket = assertInstanceOf(
                        ClientboundSetEntityMotionPacket.class, packet);
                ByteBuf wire = encodePacket(protocol, nativePacket);
                try {
                    FriendlyByteBuf fields = new FriendlyByteBuf(wire.duplicate());
                    assertEquals(PACKET_ID, fields.readVarInt());
                    return new EncodedPacket(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            PACKET_ID,
                            bytes(fields));
                } finally {
                    wire.release();
                }
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                return decodePayload(protocol, frame.payload());
            }
        };
    }

    private static PacketCodec<Object> failingCodec(AtomicInteger decodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                throw new AssertionError("descriptor mismatch must not encode");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("descriptor mismatch must not decode");
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
