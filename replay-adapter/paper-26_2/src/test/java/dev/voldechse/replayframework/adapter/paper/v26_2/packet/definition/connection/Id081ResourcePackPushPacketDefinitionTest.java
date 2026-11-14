package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Style;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id081ResourcePackPushPacketDefinitionTest {

    private static final int PACKET_ID = 81;
    private static final int MAX_URL_CHARACTERS = 32_767;
    private static final int MAX_HASH_CHARACTERS = 40;
    private static final String TYPE_NAME = "play.clientbound.minecraft:resource_pack_push";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID[] PACK_IDS = {
        new UUID(0L, 0L),
        new UUID(1L, 2L),
        UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
        new UUID(Long.MIN_VALUE, Long.MAX_VALUE),
        new UUID(-1L, -1L)
    };
    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesResourcePackPushAsCapturedButUnsafeConnectionControl() {
        var definition = new Id081ResourcePackPushPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x51, Id081ResourcePackPushPacketDefinition.PACKET_ID);
        assertEquals("minecraft:resource_pack_push", Id081ResourcePackPushPacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInConnectionDomainCatalogAndPaperRegistry() {
        var domainMatches = ConnectionPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id081ResourcePackPushPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id081ResourcePackPushPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.CONTROL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertFalse(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.UNSAFE, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void blocksValidAndMalformedFramesBeforePlaybackCodecAccess() {
        var definition = new Id081ResourcePackPushPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[] {1, 2, 3},
            new byte[] {(byte) 0x80},
            new byte[] {(byte) 0xFF, (byte) 0xFF}
        }) {
            IncompatibleAdapterException failure = assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(frame(payload), descriptor(), codec, rewriter()));
            assertEquals(
                    "packet descriptor is not permitted for playback: " + TYPE_NAME,
                    failure.getMessage());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void rejectsAlreadyDecodedPacketsWithoutInvokingPacketRewrite() {
        IncompatibleAdapterException failure = assertThrows(
                IncompatibleAdapterException.class,
                () -> new Id081ResourcePackPushPacketDefinition()
                        .rewrite(new Object(), descriptor(), rewriter()));

        assertEquals(
                "packet descriptor is not permitted for playback: " + TYPE_NAME,
                failure.getMessage());
    }

    @Test
    void validatesPlaybackDescriptorBeforeAnyCodecAccess() {
        var definition = new Id081ResourcePackPushPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, new AtomicInteger());

        assertThrows(NullPointerException.class, () -> definition.handlePlayback(
                null, descriptor(), codec, rewriter()));
        assertThrows(NullPointerException.class, () -> definition.handlePlayback(
                frame(new byte[0]), null, codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(), null, rewriter()));

        PacketDescriptor mismatchedId = new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                PACKET_ID + 1,
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.UNSAFE);
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), mismatchedId, codec, rewriter()));

        assertEquals(0, decodes.get());
    }

    @Test
    void nativePlayCodecRoundTripsEveryRequiredAndPromptCombination() {
        ProtocolInfo<?> protocol = clientboundProtocol();
        List<Optional<Component>> prompts = List.of(
                Optional.empty(),
                Optional.of(Component.literal("")),
                Optional.of(Component.literal("Download resource pack? 雪 😀")),
                Optional.of(Component.literal("Custom pack: ")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true))
                        .append(Component.translatableWithFallback(
                                "replay.resource_pack.prompt", "Pack %s", "seasonal"))));

        int variation = 0;
        for (boolean required : new boolean[] {false, true}) {
            for (Optional<Component> prompt : prompts) {
                UUID id = PACK_IDS[variation % PACK_IDS.length];
                String url = variation % 2 == 0
                        ? "https://assets.example.invalid/packs/" + variation + ".zip"
                        : "https://例.invalid/资源包/pack-😀.zip";
                String hash = variation % 2 == 0 ? SHA1 : "non-hex-but-allowed-by-the-wire";
                assertNativeRoundTrip(protocol, id, url, hash, required, prompt);
                variation++;
            }
        }
    }

    @Test
    void nativePlayCodecPreservesUuidUrlHashAndTheirValidBoundaries() {
        ProtocolInfo<?> protocol = clientboundProtocol();
        String maximumUrl = "u".repeat(MAX_URL_CHARACTERS);
        String maximumHash = "h".repeat(MAX_HASH_CHARACTERS);

        for (UUID id : PACK_IDS) {
            assertNativeRoundTrip(protocol, id, "", "", false, Optional.empty());
            assertNativeRoundTrip(protocol, id, maximumUrl, maximumHash, true, Optional.empty());
        }

        assertNativeRoundTrip(
                protocol,
                PACK_IDS[2],
                "https://example.invalid/Grüße/资源包/😀.zip",
                "x".repeat(MAX_HASH_CHARACTERS),
                false,
                Optional.of(Component.literal("A required-looking prompt is still only text")));
    }

    @Test
    void nativeCodecRoundTripsEveryTrustedPromptContentFamily() {
        ProtocolInfo<?> protocol = clientboundProtocol();
        for (String json : List.of(
                "\"\"",
                "\"Install this pack? 雪 😀\"",
                "{\"text\":\"Pack\",\"color\":\"gold\",\"bold\":true,\"extra\":[{\"text\":\" now\",\"italic\":true}]}",
                "[\"First\",{\"text\":\" second\"}]",
                "{\"translate\":\"pack.prompt\",\"fallback\":\"Install %s\",\"with\":[\"seasonal\"]}",
                "{\"keybind\":\"key.jump\"}",
                "{\"score\":{\"name\":\"@s\",\"objective\":\"sidebar\"}}",
                "{\"selector\":\"@e[type=minecraft:zombie,limit=1]\",\"separator\":{\"text\":\", \"}}",
                "{\"nbt\":\"Items[].id\",\"plain\":true,\"storage\":\"minecraft:replay_pack\"}",
                "{\"nbt\":\"Pos[0]\",\"interpret\":true,\"block\":\"~ ~ ~\"}",
                "{\"nbt\":\"Health\",\"entity\":\"@s\"}",
                "{\"atlas\":\"minecraft:blocks\",\"sprite\":\"minecraft:stone\",\"fallback\":\"stone\"}",
                "{\"player\":{\"name\":\"Notch\"},\"hat\":false}")) {
            assertNativeRoundTrip(
                    protocol,
                    PACK_IDS[3],
                    "https://packs.example.invalid/content-family.zip",
                    SHA1,
                    true,
                    Optional.of(decodeComponent(json)));
        }
    }

    @Test
    void nativeCodecWritesFieldsInUuidUrlHashRequiredPromptOrder() {
        ProtocolInfo<?> protocol = clientboundProtocol();
        UUID id = PACK_IDS[2];
        String url = "https://packs.example.invalid/pack.zip";
        String hash = SHA1;
        Component prompt = Component.literal("Required pack")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.RED));
        ByteBuf encoded = encodePacket(protocol,
                new ClientboundResourcePackPushPacket(id, url, hash, true, Optional.of(prompt)));
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(id.getMostSignificantBits(), wire.readLong());
            assertEquals(id.getLeastSignificantBits(), wire.readLong());
            assertEquals(url, readUtf(wire));
            assertEquals(hash, readUtf(wire));
            assertTrue(wire.readBoolean());
            assertTrue(wire.readBoolean());
            assertTrue(wire.isReadable());

            ClientboundResourcePackPushPacket decoded = assertInstanceOf(
                    ClientboundResourcePackPushPacket.class, decode(protocol, encoded));
            assertEquals(id, decoded.id());
            assertEquals(url, decoded.url());
            assertEquals(hash, decoded.hash());
            assertTrue(decoded.required());
            assertEquals(Optional.of(prompt), decoded.prompt());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeCodecCanonicalizesNonzeroRequiredAndPromptFlagsAsTrue() {
        ProtocolInfo<?> protocol = clientboundProtocol();
        ClientboundResourcePackPushPacket canonical = new ClientboundResourcePackPushPacket(
                PACK_IDS[1],
                "https://pack.example.invalid/a.zip",
                SHA1,
                true,
                Optional.of(Component.literal("Install?")));
        ByteBuf canonicalBytes = encodePacket(protocol, canonical);
        try {
            byte[] original = readableBytes(canonicalBytes);
            int[] boolIndexes = booleanFieldIndexes(canonicalBytes.duplicate());
            for (int requiredFlag : new int[] {2, 0x7F, 0x80, 0xFF}) {
                for (int promptFlag : new int[] {2, 0x7F, 0x80, 0xFF}) {
                    ByteBuf input = Unpooled.wrappedBuffer(original.clone());
                    try {
                        input.setByte(boolIndexes[0], requiredFlag);
                        input.setByte(boolIndexes[1], promptFlag);
                        ClientboundResourcePackPushPacket decoded = assertInstanceOf(
                                ClientboundResourcePackPushPacket.class, decode(protocol, input));
                        assertTrue(decoded.required());
                        assertEquals(canonical.prompt(), decoded.prompt());
                    } finally {
                        input.release();
                    }
                }
            }
        } finally {
            canonicalBytes.release();
        }
    }

    @Test
    void nativeCodecRejectsOverlongUrlsAndHashesOnEncodeAndDecode() {
        ProtocolInfo<?> protocol = clientboundProtocol();

        assertThrows(RuntimeException.class, () -> encodePacket(
                protocol,
                new ClientboundResourcePackPushPacket(
                        PACK_IDS[0], "u".repeat(MAX_URL_CHARACTERS + 1), "", false, Optional.empty())));
        assertThrows(IllegalArgumentException.class, () -> new ClientboundResourcePackPushPacket(
                PACK_IDS[0], "https://example.invalid/pack.zip", "h".repeat(MAX_HASH_CHARACTERS + 1), false, Optional.empty()));

        ByteBuf longCharacterCount = Unpooled.buffer();
        try {
            writePacketIdAndUuid(longCharacterCount, PACK_IDS[0]);
            VarInt.write(longCharacterCount, MAX_URL_CHARACTERS + 1);
            longCharacterCount.writeBytes("u".repeat(MAX_URL_CHARACTERS + 1).getBytes(StandardCharsets.UTF_8));
            assertDecodeFails(protocol, longCharacterCount);
        } finally {
            longCharacterCount.release();
        }

        ByteBuf longUrlEncodedLength = Unpooled.buffer();
        try {
            writePacketIdAndUuid(longUrlEncodedLength, PACK_IDS[0]);
            VarInt.write(longUrlEncodedLength, 4 * MAX_URL_CHARACTERS + 1);
            assertDecodeFails(protocol, longUrlEncodedLength);
        } finally {
            longUrlEncodedLength.release();
        }

        ByteBuf longHash = Unpooled.buffer();
        try {
            writePacketIdAndUuid(longHash, PACK_IDS[0]);
            writeUtf(longHash, "https://example.invalid/pack.zip");
            writeUtf(longHash, "h".repeat(MAX_HASH_CHARACTERS + 1));
            assertDecodeFails(protocol, longHash);
        } finally {
            longHash.release();
        }
    }

    @Test
    void nativeCodecDecodesMalformedUtf8WithReplacementAndReencodesCanonicalUtf8() {
        ProtocolInfo<?> protocol = clientboundProtocol();
        ByteBuf input = Unpooled.buffer();
        ByteBuf expectedCanonical = Unpooled.buffer();
        try {
            writePacketIdAndUuid(input, PACK_IDS[0]);
            VarInt.write(input, 1);
            input.writeByte(0xFF);
            writeUtf(input, "");
            input.writeBoolean(false);
            input.writeBoolean(false);

            ClientboundResourcePackPushPacket decoded = assertInstanceOf(
                    ClientboundResourcePackPushPacket.class, decode(protocol, input));
            assertEquals("\uFFFD", decoded.url());
            assertEquals("", decoded.hash());
            assertFalse(decoded.required());
            assertEquals(Optional.empty(), decoded.prompt());
            assertFalse(input.isReadable());

            encode(protocol, expectedCanonical, decoded);
            ByteBuf canonical = expectedCanonical.duplicate();
            assertEquals(PACKET_ID, VarInt.read(canonical));
            assertEquals(PACK_IDS[0].getMostSignificantBits(), canonical.readLong());
            assertEquals(PACK_IDS[0].getLeastSignificantBits(), canonical.readLong());
            assertEquals("\uFFFD", readUtf(canonical));
            assertEquals("", readUtf(canonical));
            assertFalse(canonical.readBoolean());
            assertFalse(canonical.readBoolean());
            assertFalse(canonical.isReadable());
        } finally {
            input.release();
            expectedCanonical.release();
        }
    }

    @Test
    void nativeCodecRejectsMissingAndTruncatedFieldsIncludingPresentPrompt() {
        ProtocolInfo<?> protocol = clientboundProtocol();
        assertMalformed(protocol, new byte[0]);
        assertMalformed(protocol, new byte[] {0x50});

        ByteBuf complete = encodePacket(protocol, new ClientboundResourcePackPushPacket(
                PACK_IDS[2],
                "https://pack.example.invalid/pack.zip",
                SHA1,
                true,
                Optional.of(Component.literal("Install the pack?"))));
        try {
            ByteBuf wire = complete.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            byte[] payload = new byte[wire.readableBytes()];
            wire.readBytes(payload);
            for (int length = 0; length < payload.length; length++) {
                assertMalformed(protocol, java.util.Arrays.copyOf(payload, length));
            }
        } finally {
            complete.release();
        }
    }

    @Test
    void nativeCodecLeavesFollowingPacketFrameBytesUntouched() {
        ProtocolInfo<?> protocol = clientboundProtocol();
        ClientboundResourcePackPushPacket first = new ClientboundResourcePackPushPacket(
                PACK_IDS[0], "https://one.example.invalid/pack.zip", "", false, Optional.empty());
        ClientboundResourcePackPushPacket second = new ClientboundResourcePackPushPacket(
                PACK_IDS[1],
                "https://two.example.invalid/pack.zip",
                SHA1,
                true,
                Optional.of(Component.literal("Second pack")));
        ByteBuf input = Unpooled.buffer();
        try {
            encode(protocol, input, first);
            encode(protocol, input, second);
            input.writeByte(0x5A);

            ClientboundResourcePackPushPacket decodedFirst = assertInstanceOf(
                    ClientboundResourcePackPushPacket.class, decode(protocol, input));
            ClientboundResourcePackPushPacket decodedSecond = assertInstanceOf(
                    ClientboundResourcePackPushPacket.class, decode(protocol, input));
            assertEquals(first, decodedFirst);
            assertEquals(second, decodedSecond);
            assertEquals(0x5A, input.readUnsignedByte());
            assertFalse(input.isReadable());
        } finally {
            input.release();
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            UUID id,
            String url,
            String hash,
            boolean required,
            Optional<Component> prompt) {
        ClientboundResourcePackPushPacket expected =
                new ClientboundResourcePackPushPacket(id, url, hash, required, prompt);
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(id.getMostSignificantBits(), wire.readLong());
            assertEquals(id.getLeastSignificantBits(), wire.readLong());
            assertEquals(url, readUtf(wire));
            assertEquals(hash, readUtf(wire));
            assertEquals(required, wire.readBoolean());
            assertEquals(prompt.isPresent(), wire.readBoolean());
            assertEquals(prompt.isPresent(), wire.isReadable());

            ClientboundResourcePackPushPacket decoded = assertInstanceOf(
                    ClientboundResourcePackPushPacket.class, decode(protocol, encoded));
            assertEquals(expected, decoded);
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static Component decodeComponent(String json) {
        return ComponentSerialization.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow();
    }

    private static int[] booleanFieldIndexes(ByteBuf encoded) {
        ByteBuf fields = encoded.duplicate();
        VarInt.read(fields);
        fields.skipBytes(Long.BYTES * 2);
        readUtf(fields);
        readUtf(fields);
        int requiredIndex = fields.readerIndex();
        fields.skipBytes(1);
        return new int[] {requiredIndex, fields.readerIndex()};
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf input = Unpooled.buffer();
        try {
            VarInt.write(input, PACKET_ID);
            input.writeBytes(payload);
            assertDecodeFails(protocol, input);
        } finally {
            input.release();
        }
    }

    private static void assertDecodeFails(ProtocolInfo<?> protocol, ByteBuf input) {
        assertThrows(RuntimeException.class, () -> decode(protocol, input));
    }

    private static void writePacketIdAndUuid(ByteBuf output, UUID id) {
        VarInt.write(output, PACKET_ID);
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    private static void writeUtf(ByteBuf output, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        VarInt.write(output, encoded.length);
        output.writeBytes(encoded);
    }

    private static String readUtf(ByteBuf input) {
        int length = VarInt.read(input);
        String value = input.toString(input.readerIndex(), length, StandardCharsets.UTF_8);
        input.skipBytes(length);
        return value;
    }

    private static byte[] readableBytes(ByteBuf input) {
        byte[] bytes = new byte[input.readableBytes()];
        input.getBytes(input.readerIndex(), bytes);
        return bytes;
    }

    private static ByteBuf encodePacket(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf output = Unpooled.buffer();
        try {
            encode(protocol, output, packet);
            return output;
        } catch (RuntimeException | Error failure) {
            output.release();
            throw failure;
        }
    }

    private static ProtocolInfo<?> clientboundProtocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID), Set.of(OBSERVER_ID), Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                PACKET_ID,
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.UNSAFE);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes, AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("resource_pack_push must not encode during playback");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("resource_pack_push must not decode during playback");
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
