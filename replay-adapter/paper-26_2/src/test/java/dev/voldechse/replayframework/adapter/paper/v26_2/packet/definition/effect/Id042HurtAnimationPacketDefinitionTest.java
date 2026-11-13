package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

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
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id042HurtAnimationPacketDefinitionTest {

    private static final int PACKET_ID = 42;
    private static final String TYPE_NAME = "play.clientbound.minecraft:hurt_animation";
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
    void classifiesHurtAnimationAsAnEphemeralIdentityBearingEffect() {
        var definition = new Id042HurtAnimationPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x2A, Id042HurtAnimationPacketDefinition.PACKET_ID);
        assertEquals("minecraft:hurt_animation", Id042HurtAnimationPacketDefinition.IDENTIFIER);
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
        assertInstanceOf(Id042HurtAnimationPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id042HurtAnimationPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
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
    }

    @Test
    void rewritesOnlyTheTargetIdentityAndPreservesUnnormalizedFiniteYaw() {
        var definition = new Id042HurtAnimationPacketDefinition();
        var context = rewriter();
        int recordedEntityId = 41;
        int mappedEntityId = context.mapEntityId(recordedEntityId);
        float[] yaws = {
            -Float.MAX_VALUE,
            -1080.25F,
            -360.0F,
            -180.0F,
            -90.0F,
            -Float.MIN_VALUE,
            -0.0F,
            0.0F,
            Float.MIN_VALUE,
            90.0F,
            180.0F,
            360.0F,
            720.5F,
            Float.MAX_VALUE
        };

        for (float yaw : yaws) {
            ClientboundHurtAnimationPacket original =
                    new ClientboundHurtAnimationPacket(recordedEntityId, yaw);
            Id042HurtAnimationPacketDefinition.Value read = definition.read(original);
            assertEquals(recordedEntityId, read.entityId());
            assertEquals(Float.floatToRawIntBits(yaw), Float.floatToRawIntBits(read.yaw()));

            Id042HurtAnimationPacketDefinition.Value rewritten = definition
                    .rewrite(read, context)
                    .value()
                    .orElseThrow();
            assertEquals(mappedEntityId, rewritten.entityId());
            assertEquals(Float.floatToRawIntBits(yaw), Float.floatToRawIntBits(rewritten.yaw()));

            ClientboundHurtAnimationPacket rebuilt = definition.rebuild(rewritten);
            assertEquals(mappedEntityId, rebuilt.id());
            assertEquals(Float.floatToRawIntBits(yaw), Float.floatToRawIntBits(rebuilt.yaw()));
        }

        assertNotEquals(recordedEntityId, mappedEntityId);
    }

    @Test
    void mapsTargetIdsWithoutTreatingAnyValueAsAnAbsentEntitySentinel() {
        var definition = new Id042HurtAnimationPacketDefinition();
        var context = rewriter();

        for (int recordedId : new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
            Id042HurtAnimationPacketDefinition.Value rewritten = definition
                    .rewrite(new Id042HurtAnimationPacketDefinition.Value(recordedId, 45.0F), context)
                    .value()
                    .orElseThrow();
            assertEquals(context.mapEntityId(recordedId), rewritten.entityId());
            assertEquals(45.0F, rewritten.yaw());
        }

        assertNotEquals(-1, context.mapEntityId(-1));
    }

    @Test
    void rejectsNonFiniteYawValues() {
        var definition = new Id042HurtAnimationPacketDefinition();

        for (float yaw : new float[] {
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY
        }) {
            assertThrows(
                    IncompatibleAdapterException.class,
                    () -> new Id042HurtAnimationPacketDefinition.Value(1, yaw));
            assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.read(new ClientboundHurtAnimationPacket(1, yaw)));
        }
    }

    @Test
    void playbackRewritesAndEncodesTheRebuiltAnimation() {
        var definition = new Id042HurtAnimationPacketDefinition();
        var context = rewriter();
        ClientboundHurtAnimationPacket original =
                new ClientboundHurtAnimationPacket(501, -270.5F);
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = codec(original, decodes, encodes);

        PacketRewriteResult result = definition.handlePlayback(
                frame(new byte[0]), descriptor(), codec, context);
        ClientboundHurtAnimationPacket rewritten = assertInstanceOf(
                ClientboundHurtAnimationPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(context.mapEntityId(501), rewritten.id());
        assertEquals(-270.5F, rewritten.yaw());
        assertEquals(PACKET_ID, definition.encode(rewritten, descriptor(), codec).packetId());
        assertEquals(1, decodes.get());
        assertEquals(1, encodes.get());
    }

    @Test
    void nativeProtocolEncodesVarIntIdThenExactFloatYaw() {
        ProtocolInfo<?> protocol = protocol();
        int[] entityIds = {
            Integer.MIN_VALUE, -1, 0, 1, 41, 300, Integer.MAX_VALUE
        };
        float[] yaws = {
            Float.NEGATIVE_INFINITY,
            -Float.MAX_VALUE,
            -360.0F,
            -0.0F,
            0.0F,
            180.0F,
            720.5F,
            Float.MAX_VALUE,
            Float.POSITIVE_INFINITY,
            Float.intBitsToFloat(0x7FC12345)
        };

        for (int entityId : entityIds) {
            for (float yaw : yaws) {
                assertNativeRoundTrip(protocol, entityId, yaw);
            }
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPayloadPrefixAndOverlongEntityVarInt() {
        ProtocolInfo<?> protocol = protocol();
        byte[] complete = encodePacketBytes(
                protocol, new ClientboundHurtAnimationPacket(300, -23.75F));
        ByteBuf wire = Unpooled.wrappedBuffer(complete);
        byte[] payload;
        try {
            assertEquals(PACKET_ID, readVarInt(wire));
            payload = new byte[wire.readableBytes()];
            wire.readBytes(payload);
        } finally {
            wire.release();
        }

        for (int length = 0; length < payload.length; length++) {
            assertMalformed(protocol, java.util.Arrays.copyOf(payload, length));
        }
        assertMalformed(protocol, new byte[] {
            (byte) 0x80, (byte) 0x80, (byte) 0x80,
            (byte) 0x80, (byte) 0x80, 0, 0, 0, 0, 0
        });
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            int entityId,
            float yaw) {
        ClientboundHurtAnimationPacket expected =
                new ClientboundHurtAnimationPacket(entityId, yaw);
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            assertEquals(entityId, readVarInt(wire));
            assertEquals(Float.floatToRawIntBits(yaw), wire.readInt());
            assertFalse(wire.isReadable());

            ClientboundHurtAnimationPacket decoded = assertInstanceOf(
                    ClientboundHurtAnimationPacket.class, decode(protocol, encoded));
            assertEquals(entityId, decoded.id());
            assertEquals(Float.floatToRawIntBits(yaw), Float.floatToRawIntBits(decoded.yaw()));
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
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

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> codec(
            ClientboundHurtAnimationPacket decoded,
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                assertInstanceOf(ClientboundHurtAnimationPacket.class, packet);
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

    private static void writeVarInt(ByteBuf buffer, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            buffer.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer.writeByte(remaining);
    }
}
