package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.connection;

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
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id021CookieRequestPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:cookie_request";

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesCookieRequestAsUnsafeConnectionControl() {
        var definition = new Id021CookieRequestPacketDefinition();

        assertEquals(21, definition.packetId());
        assertEquals(0x15, Id021CookieRequestPacketDefinition.PACKET_ID);
        assertEquals("minecraft:cookie_request", Id021CookieRequestPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.CONTROL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertFalse(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.UNSAFE,
                definition.classification().playbackScope());
    }

    @Test
    void registersExactlyOnceInTheConnectionDomain() {
        var matchingDefinitions = ConnectionPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, matchingDefinitions.size());
        assertInstanceOf(Id021CookieRequestPacketDefinition.class, matchingDefinitions.getFirst());
    }

    @Test
    void blocksEveryRecordedPayloadBeforePlaybackDecode() {
        var definition = new Id021CookieRequestPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[] {1, 'a'},
            new byte[] {(byte) 0x80},
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07},
            new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80}
        }) {
            IncompatibleAdapterException failure = assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(frame(payload), descriptor(), codec, null));

            assertEquals(
                    "packet descriptor is not permitted for playback: " + TYPE_NAME,
                    failure.getMessage());
        }

        assertEquals(0, decodes.get());
    }

    @Test
    void blocksAlreadyDecodedValuesWithoutInspectingTheirType() {
        var definition = new Id021CookieRequestPacketDefinition();

        IncompatibleAdapterException failure = assertThrows(
                IncompatibleAdapterException.class,
                () -> definition.rewrite(new Object(), descriptor(), null));

        assertEquals(
                "packet descriptor is not permitted for playback: " + TYPE_NAME,
                failure.getMessage());
    }

    @Test
    void validatesNullAndMismatchedDescriptorsBeforeAnyCodecAccess() {
        var definition = new Id021CookieRequestPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes);

        assertThrows(NullPointerException.class, () -> definition.handlePlayback(
                null, descriptor(), codec, null));
        assertThrows(NullPointerException.class, () -> definition.handlePlayback(
                frame(new byte[0]), null, codec, null));

        PacketDescriptor wrongId = new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                22,
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                "paper-26.3/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.UNSAFE);
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), wrongId, codec, null));

        assertEquals(0, decodes.get());
    }

    @Test
    void nativeProtocolRoundTripsEveryIdentifierShapeAndLengthPrefixBoundary() {
        ProtocolInfo<?> protocol = protocol();

        for (String key : new String[] {
            "minecraft:cookie",
            "custom:path/to.cookie_value-part",
            identifierOfLength(127),
            identifierOfLength(128),
            identifierOfLength(16_383),
            identifierOfLength(16_384),
            identifierOfLength(21_845)
        }) {
            assertNativeRoundTrip(protocol, Identifier.parse(key));
        }
    }

    @Test
    void nativeProtocolCanonicalizesAnImplicitMinecraftNamespace() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf input = Unpooled.buffer();
        ByteBuf reencoded = Unpooled.buffer();
        try {
            writeVarInt(input, 21);
            writeUtf(input, "cookie/path");

            ClientboundCookieRequestPacket decoded = assertInstanceOf(
                    ClientboundCookieRequestPacket.class, decode(protocol, input));
            assertEquals(Identifier.parse("minecraft:cookie/path"), decoded.key());
            assertFalse(input.isReadable());

            encode(protocol, reencoded, decoded);
            assertEquals(21, readVarInt(reencoded));
            assertEquals("minecraft:cookie/path", readUtf(reencoded));
            assertFalse(reencoded.isReadable());
        } finally {
            input.release();
            reencoded.release();
        }
    }

    @Test
    void nativeProtocolKeepsRepeatedRequestsIndependentAndPayloadFree() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf input = Unpooled.buffer();
        try {
            encode(protocol, input, new ClientboundCookieRequestPacket(Identifier.parse("one:key")));
            encode(protocol, input, new ClientboundCookieRequestPacket(Identifier.parse("two:key")));

            ClientboundCookieRequestPacket first = assertInstanceOf(
                    ClientboundCookieRequestPacket.class, decode(protocol, input));
            ClientboundCookieRequestPacket second = assertInstanceOf(
                    ClientboundCookieRequestPacket.class, decode(protocol, input));

            assertEquals(Identifier.parse("one:key"), first.key());
            assertEquals(Identifier.parse("two:key"), second.key());
            assertFalse(input.isReadable());
        } finally {
            input.release();
        }
    }

    @Test
    void nativeProtocolRejectsMalformedTruncatedInvalidAndOversizedIdentifiers() {
        ProtocolInfo<?> protocol = protocol();

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {(byte) 0x80},
            declaredUtf(3, new byte[] {'a'}),
            declaredUtf(1, new byte[] {(byte) 0x80}),
            encodedUtf("Invalid:cookie"),
            encodedUtf("minecraft:Invalid"),
            encodedUtf("minecraft:bad?path"),
            encodedUtf(identifierOfLength(21_846)),
            declaredUtf(32_768, new byte[0])
        }) {
            ByteBuf input = Unpooled.buffer();
            try {
                writeVarInt(input, 21);
                input.writeBytes(payload);
                assertThrows(RuntimeException.class, () -> decode(protocol, input));
            } finally {
                input.release();
            }
        }

    }

    @Test
    void nativeProtocolLeavesFollowingFrameBytesUntouched() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf input = Unpooled.buffer();
        try {
            encode(protocol, input, new ClientboundCookieRequestPacket(Identifier.parse("test:key")));
            input.writeByte(0x5A);

            ClientboundCookieRequestPacket decoded = assertInstanceOf(
                    ClientboundCookieRequestPacket.class, decode(protocol, input));
            assertEquals(Identifier.parse("test:key"), decoded.key());
            assertEquals(0x5A, input.readUnsignedByte());
            assertFalse(input.isReadable());
        } finally {
            input.release();
        }
    }

    @Test
    void correlatedResponseSupportsMissingEmptyBinaryAndMaximumPayloads() {
        Identifier key = Identifier.parse("replay:test_cookie");

        for (byte[] payload : new byte[][] {
            null,
            new byte[0],
            new byte[] {0, 1, (byte) 0x80, (byte) 0xFF},
            new byte[5_120]
        }) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ServerboundCookieResponsePacket.STREAM_CODEC.encode(
                        buffer, new ServerboundCookieResponsePacket(key, payload));
                ServerboundCookieResponsePacket decoded =
                        ServerboundCookieResponsePacket.STREAM_CODEC.decode(buffer);

                assertEquals(key, decoded.key());
                assertArrayEquals(payload, decoded.payload());
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void correlatedResponseRejectsPayloadLargerThanFiveKibibytes() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var response = new ServerboundCookieResponsePacket(
                    Identifier.parse("replay:test_cookie"), new byte[5_121]);

            assertThrows(
                    RuntimeException.class,
                    () -> ServerboundCookieResponsePacket.STREAM_CODEC.encode(buffer, response));
        } finally {
            buffer.release();
        }
    }

    @Test
    void correlatedResponseDecoderRejectsAnOversizedDeclaredPayload() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeIdentifier(Identifier.parse("replay:test_cookie"));
            buffer.writeBoolean(true);
            buffer.writeVarInt(5_121);
            buffer.writeZero(5_121);

            assertThrows(
                    RuntimeException.class,
                    () -> ServerboundCookieResponsePacket.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            Identifier expectedKey) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, new ClientboundCookieRequestPacket(expectedKey));

            ByteBuf wireView = encoded.duplicate();
            assertEquals(21, readVarInt(wireView));
            assertEquals(expectedKey.toString(), readUtf(wireView));
            assertFalse(wireView.isReadable());

            ClientboundCookieRequestPacket decoded = assertInstanceOf(
                    ClientboundCookieRequestPacket.class, decode(protocol, encoded));
            assertEquals(expectedKey, decoded.key());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                21,
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                "paper-26.3/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.UNSAFE);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 21, payload);
    }

    private static PacketCodec<Object> failingCodec(AtomicInteger decodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                throw new AssertionError("blocked cookie_request must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("blocked cookie_request must not decode");
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
