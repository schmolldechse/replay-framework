package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id135ProjectilePowerPacketDefinitionTest {

    private static final int PACKET_ID = 135;
    private static final String TYPE_NAME = "play.clientbound.minecraft:projectile_power";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000135");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000136");
    private static final double NAN_WITH_PAYLOAD = Double.longBitsToDouble(0x7ff8000000000042L);
    private static final List<Double> ACCELERATION_VALUES = List.of(
            0.1D,
            0.0D,
            -0.0D,
            Double.MIN_VALUE,
            -Double.MAX_VALUE,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            NAN_WITH_PAYLOAD);
    private static final List<Integer> ENTITY_ID_BOUNDARIES = List.of(
            Integer.MIN_VALUE, -1, 0, 1, 127, 128, 16_383, 16_384, Integer.MAX_VALUE);

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesAndRegistersProjectilePowerAsCheckpointRelevantEntityState() {
        var definition = new Id135ProjectilePowerPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x87, Id135ProjectilePowerPacketDefinition.PACKET_ID);
        assertEquals("minecraft:projectile_power", Id135ProjectilePowerPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
        assertEquals(
                Paper26_2CheckpointEncoder.CheckpointPacketFamily.ENTITY_METADATA,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));

        var domainMatches = EntityPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id135ProjectilePowerPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id135ProjectilePowerPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void nativeCodecUsesEntityVarIntAndPreservesAccelerationDoubleBits() {
        ProtocolInfo<?> protocol = protocol();
        int entityId = 0x123456;

        for (double acceleration : ACCELERATION_VALUES) {
            ClientboundProjectilePowerPacket expected = packet(entityId, acceleration);
            ByteBuf encoded = encodePacket(protocol, expected);
            try {
                byte[] expectedWire = bytes(encoded);
                FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());

                assertEquals(PACKET_ID, fields.readVarInt());
                assertEquals(entityId, fields.readVarInt());
                assertEquals(
                        Double.doubleToRawLongBits(acceleration), fields.readLong(),
                        "wire double bits for " + acceleration);
                assertFalse(fields.isReadable());

                ClientboundProjectilePowerPacket decoded = assertInstanceOf(
                        ClientboundProjectilePowerPacket.class, decode(protocol, encoded));
                assertEquals(entityId, decoded.getId());
                assertDoubleBitsEqual(acceleration, decoded.getAccelerationPower());

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
    }

    @Test
    void nativeCodecAcceptsTheFullEntityIdVarIntRange() {
        ProtocolInfo<?> protocol = protocol();

        for (int entityId : ENTITY_ID_BOUNDARIES) {
            ClientboundProjectilePowerPacket decoded = roundTrip(
                    protocol, packet(entityId, 0.375D));
            assertEquals(entityId, decoded.getId());
            assertDoubleBitsEqual(0.375D, decoded.getAccelerationPower());
        }
    }

    @Test
    void readRewriteAndRebuildKeepAccelerationBitsWhileMappingTheProjectileId() {
        var definition = new Id135ProjectilePowerPacketDefinition();
        int recordedEntityId = 1_501;
        double acceleration = NAN_WITH_PAYLOAD;
        ClientboundProjectilePowerPacket original = packet(recordedEntityId, acceleration);

        var value = definition.read(original);
        assertEquals(recordedEntityId, value.entityId());
        assertDoubleBitsEqual(acceleration, value.accelerationPower());

        var context = rewriter();
        var rewrittenValue = definition.rewrite(value, context).value().orElseThrow();
        assertEquals(context.mapEntityId(recordedEntityId), rewrittenValue.entityId());
        assertNotEquals(recordedEntityId, rewrittenValue.entityId());
        assertDoubleBitsEqual(acceleration, rewrittenValue.accelerationPower());

        ClientboundProjectilePowerPacket rebuilt = definition.rebuild(rewrittenValue);
        assertEquals(rewrittenValue.entityId(), rebuilt.getId());
        assertDoubleBitsEqual(acceleration, rebuilt.getAccelerationPower());
    }

    @Test
    void playbackRewritesOnlyTheEntityIdentityAndEncodesProjectileAcceleration() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id135ProjectilePowerPacketDefinition();
        var context = rewriter();
        PacketCodec<Object> codec = packetCodec(protocol);
        int recordedEntityId = 1_601;
        double acceleration = -0.000_125D;

        PacketRewriteResult result = definition.handlePlayback(
                frame(body(protocol, packet(recordedEntityId, acceleration))),
                descriptor(),
                codec,
                context);

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        ClientboundProjectilePowerPacket rewritten = assertInstanceOf(
                ClientboundProjectilePowerPacket.class, result.packet().orElseThrow());
        assertEquals(context.mapEntityId(recordedEntityId), rewritten.getId());
        assertDoubleBitsEqual(acceleration, rewritten.getAccelerationPower());

        PacketCodec.EncodedPacket encoded = definition.encode(rewritten, descriptor(), codec);
        assertEquals(PacketPhase.PLAY, encoded.phase());
        assertEquals(PacketDescriptor.Direction.CLIENTBOUND, encoded.direction());
        assertEquals(PACKET_ID, encoded.packetId());

        ClientboundProjectilePowerPacket decoded = assertInstanceOf(
                ClientboundProjectilePowerPacket.class,
                decodePayload(protocol, encoded.payload()));
        assertEquals(rewritten.getId(), decoded.getId());
        assertDoubleBitsEqual(rewritten.getAccelerationPower(), decoded.getAccelerationPower());
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedBodyAndLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completeBody = body(protocol, packet(23, -0.25D));

        for (int length = 0; length < completeBody.length; length++) {
            assertNativeDecodeFails(protocol, java.util.Arrays.copyOf(completeBody, length));
        }

        ByteBuf wire = packetWire(completeBody);
        try {
            wire.writeByte(0x7A);
            ClientboundProjectilePowerPacket decoded = assertInstanceOf(
                    ClientboundProjectilePowerPacket.class, decode(protocol, wire));

            assertEquals(23, decoded.getId());
            assertDoubleBitsEqual(-0.25D, decoded.getAccelerationPower());
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void rejectsDescriptorMismatchBeforeNativeCodecUse() {
        var definition = new Id135ProjectilePowerPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes);

        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(TYPE_NAME, PACKET_ID + 1), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor("play.clientbound.minecraft:other", PACKET_ID),
                codec, rewriter()));
        assertEquals(0, decodes.get());
    }

    private static ClientboundProjectilePowerPacket packet(int entityId, double accelerationPower) {
        return new ClientboundProjectilePowerPacket(entityId, accelerationPower);
    }

    private static void assertDoubleBitsEqual(double expected, double actual) {
        assertEquals(
                Double.doubleToRawLongBits(expected),
                Double.doubleToRawLongBits(actual));
    }

    private static ClientboundProjectilePowerPacket roundTrip(
            ProtocolInfo<?> protocol, ClientboundProjectilePowerPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            return assertInstanceOf(ClientboundProjectilePowerPacket.class, decode(protocol, encoded));
        } finally {
            encoded.release();
        }
    }

    private static byte[] body(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded));
            return bytes(encoded);
        } finally {
            encoded.release();
        }
    }

    private static void assertNativeDecodeFails(ProtocolInfo<?> protocol, byte[] body) {
        ByteBuf wire = packetWire(body);
        try {
            assertThrows(RuntimeException.class, () -> decode(protocol, wire));
        } finally {
            wire.release();
        }
    }

    private static ByteBuf packetWire(byte[] body) {
        ByteBuf wire = Unpooled.buffer();
        VarInt.write(wire, PACKET_ID);
        wire.writeBytes(body);
        return wire;
    }

    private static ByteBuf encodePacket(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, packet);
            return encoded;
        } catch (RuntimeException | Error failure) {
            encoded.release();
            throw failure;
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] result = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), result);
        return result;
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
    }

    private static Object decodePayload(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf wire = packetWire(payload);
        try {
            return decode(protocol, wire);
        } finally {
            wire.release();
        }
    }

    private static PacketCodec<Object> packetCodec(ProtocolInfo<?> protocol) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                ByteBuf wire = encodePacket(protocol, assertInstanceOf(Packet.class, packet));
                try {
                    int packetId = VarInt.read(wire);
                    return new EncodedPacket(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            packetId,
                            bytes(wire));
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
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("descriptor mismatch must not decode");
            }
        };
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(TYPE_NAME, PACKET_ID);
    }

    private static PacketDescriptor descriptor(String typeName, int packetId) {
        return new PacketDescriptor(
                typeName,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "paper-26.2/" + typeName,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
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
