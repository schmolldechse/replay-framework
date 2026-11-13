package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

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
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id024CustomPayloadPacketDefinitionTest {

    private static final int MAX_PAYLOAD_SIZE = 1_048_576;
    private static final String TYPE_NAME = "play.clientbound.minecraft:custom_payload";
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
    void classifiesCustomPayloadAsViewerLocalConfigurableData() {
        var definition = new Id024CustomPayloadPacketDefinition();

        assertEquals(24, definition.packetId());
        assertEquals(0x18, Id024CustomPayloadPacketDefinition.PACKET_ID);
        assertEquals("minecraft:custom_payload", Id024CustomPayloadPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.CONFIGURABLE, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());
    }

    @Test
    void registersExactlyOnceInTheConnectionDomainAndCatalog() {
        var domainMatches = ConnectionPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id024CustomPayloadPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id024CustomPayloadPacketDefinition.class, catalogMatches.getFirst());
    }

    @Test
    void dropsEveryRecordedPayloadBeforeDecodeOrEncode() {
        var definition = new Id024CustomPayloadPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[] {1, 'a'},
            new byte[] {(byte) 0x80},
            new byte[] {3, 'a', ':', 'b', 0, 1, (byte) 0xFF},
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                    (byte) 0xFF, 0x07}
        }) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());

            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingDecodedType() {
        PacketRewriteResult result = new Id024CustomPayloadPacketDefinition()
                .rewrite(new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void validatesNullAndDescriptorBoundariesBeforeApplyingLocalPolicy() {
        var definition = new Id024CustomPayloadPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), null, codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), descriptor(), null, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), descriptor(), codec, null));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(23, true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(24, false, PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(24, true, PacketDescriptor.PlaybackScope.SAFE),
                codec, rewriter()));
    }

    @Test
    void nativeProtocolRoundTripsBrandPayloads() {
        ProtocolInfo<?> protocol = protocol();

        for (String brand : new String[] {
            "",
            "vanilla",
            "Paper",
            "Grüße-雪-😀",
            "a".repeat(127),
            "é".repeat(32_767)
        }) {
            ByteBuf encoded = encodePacket(
                    protocol, new ClientboundCustomPayloadPacket(new BrandPayload(brand)));
            try {
                ByteBuf wire = encoded.duplicate();
                assertEquals(24, readVarInt(wire));
                assertEquals("minecraft:brand", readUtf(wire));
                assertEquals(brand, readUtf(wire));
                assertFalse(wire.isReadable());

                ClientboundCustomPayloadPacket decoded = assertInstanceOf(
                        ClientboundCustomPayloadPacket.class, decode(protocol, encoded));
                BrandPayload payload = assertInstanceOf(BrandPayload.class, decoded.payload());
                assertEquals(brand, payload.brand());
                assertFalse(encoded.isReadable());
            } finally {
                encoded.release();
            }
        }
    }

    @Test
    void nativeProtocolPreservesOpaqueBytesAtEveryPayloadBoundary() {
        ProtocolInfo<?> protocol = protocol();
        Identifier channel = Identifier.parse("replay:opaque/data");

        for (int size : new int[] {0, 1, 127, 128, 255, 16_384, MAX_PAYLOAD_SIZE}) {
            byte[] data = patternedBytes(size);
            assertOpaqueRoundTrip(protocol, channel, data);
        }

        byte[] everyByte = new byte[256];
        for (int index = 0; index < everyByte.length; index++) {
            everyByte[index] = (byte) index;
        }
        assertOpaqueRoundTrip(protocol, channel, everyByte);
    }

    @Test
    void nativeProtocolRoundTripsChannelIdentifierLengthBoundaries() {
        ProtocolInfo<?> protocol = protocol();

        for (String channel : new String[] {
            "custom:path/to.channel_value-part",
            identifierOfLength(127),
            identifierOfLength(128),
            identifierOfLength(16_383),
            identifierOfLength(16_384),
            identifierOfLength(21_845)
        }) {
            assertOpaqueRoundTrip(protocol, Identifier.parse(channel), new byte[] {1, 2, 3});
        }
    }

    @Test
    void nativeProtocolTreatsEveryRemainingFrameByteAsOpaquePayload() {
        byte[] packetLikeData = new byte[] {
            23, 0, 0, 24, 15, 'm', 'i', 'n', 'e', 'c', 'r', 'a', 'f', 't', ':',
            'b', 'r', 'a', 'n', 'd', 0
        };

        assertOpaqueRoundTrip(
                protocol(), Identifier.parse("replay:framing"), packetLikeData);
    }

    @Test
    void nativeProtocolKeepsRepeatedPayloadFramesIndependent() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf first = encodePacket(protocol, new ClientboundCustomPayloadPacket(
                new DiscardedPayload(Identifier.parse("one:channel"), new byte[] {1})));
        ByteBuf second = encodePacket(protocol, new ClientboundCustomPayloadPacket(
                new DiscardedPayload(Identifier.parse("two:channel"), new byte[] {2, 3})));
        try {
            assertDiscarded(decode(protocol, first), "one:channel", new byte[] {1});
            assertDiscarded(decode(protocol, second), "two:channel", new byte[] {2, 3});
        } finally {
            first.release();
            second.release();
        }
    }

    @Test
    void nativeProtocolRejectsOversizedOpaquePayload() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf input = Unpooled.buffer(MAX_PAYLOAD_SIZE + 64);
        try {
            writeVarInt(input, 24);
            writeUtf(input, "replay:oversized");
            input.writeZero(MAX_PAYLOAD_SIZE + 1);

            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
        }
    }

    @Test
    void nativeProtocolRejectsMalformedChannelsAndBrandBodies() {
        ProtocolInfo<?> protocol = protocol();

        for (byte[] body : new byte[][] {
            new byte[0],
            new byte[] {(byte) 0x80},
            declaredUtf(3, new byte[] {'a'}),
            declaredUtf(1, new byte[] {(byte) 0x80}),
            encodedUtf("Invalid:channel"),
            encodedUtf("minecraft:Invalid"),
            encodedUtf("minecraft:bad?path"),
            encodedUtf(identifierOfLength(21_846)),
            declaredUtf(32_768, new byte[0])
        }) {
            assertMalformed(protocol, body);
        }

        for (byte[] brandData : new byte[][] {
            new byte[0],
            new byte[] {(byte) 0x80},
            declaredUtf(2, new byte[] {'x'}),
            declaredUtf(32_768, new byte[0])
        }) {
            ByteBuf input = Unpooled.buffer();
            try {
                writeVarInt(input, 24);
                writeUtf(input, "minecraft:brand");
                input.writeBytes(brandData);
                assertThrows(RuntimeException.class, () -> decode(protocol, input));
            } finally {
                input.release();
            }
        }
    }

    @Test
    void nativeBrandCodecReplacesMalformedUtf8() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf input = Unpooled.buffer();
        try {
            writeVarInt(input, 24);
            writeUtf(input, "minecraft:brand");
            input.writeBytes(declaredUtf(1, new byte[] {(byte) 0x80}));

            ClientboundCustomPayloadPacket packet = assertInstanceOf(
                    ClientboundCustomPayloadPacket.class, decode(protocol, input));
            BrandPayload brand = assertInstanceOf(BrandPayload.class, packet.payload());
            assertEquals("�", brand.brand());
            assertFalse(input.isReadable());
        } finally {
            input.release();
        }
    }

    private static void assertOpaqueRoundTrip(
            ProtocolInfo<?> protocol,
            Identifier channel,
            byte[] data) {
        ByteBuf encoded = encodePacket(protocol, new ClientboundCustomPayloadPacket(
                new DiscardedPayload(channel, data)));
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(24, readVarInt(wire));
            assertEquals(channel.toString(), readUtf(wire));
            byte[] wireData = new byte[wire.readableBytes()];
            wire.readBytes(wireData);
            assertArrayEquals(data, wireData);

            assertDiscarded(decode(protocol, encoded), channel.toString(), data);
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertDiscarded(Packet<?> packet, String channel, byte[] data) {
        ClientboundCustomPayloadPacket customPayload = assertInstanceOf(
                ClientboundCustomPayloadPacket.class, packet);
        DiscardedPayload discarded = assertInstanceOf(
                DiscardedPayload.class, customPayload.payload());
        assertEquals(Identifier.parse(channel), discarded.id());
        assertArrayEquals(data, discarded.data());
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] body) {
        ByteBuf input = Unpooled.buffer();
        try {
            writeVarInt(input, 24);
            input.writeBytes(body);
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
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

    private static byte[] patternedBytes(int size) {
        byte[] data = new byte[size];
        for (int index = 0; index < size; index++) {
            data[index] = (byte) (index * 31);
        }
        return data;
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
        return descriptor(24, true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
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
                PacketDisposition.CONFIGURABLE,
                true,
                replayable,
                false,
                "paper-26.2/" + TYPE_NAME,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 24, payload);
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local custom payload must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local custom payload must not decode");
            }
        };
    }

    private static String identifierOfLength(int length) {
        if (length < 2) {
            throw new IllegalArgumentException("identifier length must include namespace separator");
        }
        return "a:" + "b".repeat(length - 2);
    }

    private static byte[] encodedUtf(String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        return declaredUtf(encoded.length, encoded);
    }

    private static byte[] declaredUtf(int declaredLength, byte[] content) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            writeVarInt(buffer, declaredLength);
            buffer.writeBytes(content);
            byte[] result = new byte[buffer.readableBytes()];
            buffer.readBytes(result);
            return result;
        } finally {
            buffer.release();
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

    private static String readUtf(ByteBuf buffer) {
        int length = readVarInt(buffer);
        String value = buffer.toString(buffer.readerIndex(), length, StandardCharsets.UTF_8);
        buffer.skipBytes(length);
        return value;
    }

    private static void writeUtf(ByteBuf buffer, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buffer, encoded.length);
        buffer.writeBytes(encoded);
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
