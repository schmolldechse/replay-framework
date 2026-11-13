package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.PaperConnectionAccessor;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ChatTypeDecoration;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FilterMask;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.SignedMessageBody;
import net.minecraft.network.chat.Style;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id065PlayerChatPacketDefinitionTest {

    private static final int PACKET_ID = 65;
    private static final String TYPE_NAME = "play.clientbound.minecraft:player_chat";
    private static final UUID SENDER = UUID.fromString("00000000-0000-0000-0000-000000000065");
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
    void classifiesPlayerChatAsConfigurableReplayPresentationData() {
        var definition = new Id065PlayerChatPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x41, Id065PlayerChatPacketDefinition.PACKET_ID);
        assertEquals("minecraft:player_chat", Id065PlayerChatPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.CONFIGURABLE, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.SAFE,
                definition.classification().playbackScope());

        var domainMatches = ChatPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id065PlayerChatPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id065PlayerChatPacketDefinition.class, catalogMatches.getFirst());

        var registryDescriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, registryDescriptor.typeName());
        assertEquals(PacketDisposition.CONFIGURABLE, registryDescriptor.disposition());
        assertTrue(registryDescriptor.captureByDefault());
        assertTrue(registryDescriptor.replayable());
        assertFalse(registryDescriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, registryDescriptor.playbackScope());
    }

    @Test
    void rewritesCapturedChatAsDecoratedUnsignedPresentationWithoutItsSourceChain() {
        var definition = new Id065PlayerChatPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        ProtocolInfo<?> protocol = protocol(RegistryAccess.EMPTY);
        PacketCodec<Object> codec = nativeCodec(protocol, decodes, encodes);
        FilterMask partial = partialMask(1, 3);
        List<ClientboundPlayerChatPacket> sourcePackets = List.of(
                packet(300, SENDER, 7, signature(11), "signed", Instant.ofEpochMilli(1),
                        -9, List.of(new MessageSignature.Packed(2)),
                        Component.literal("unsigned display"), FilterMask.PASS_THROUGH,
                        Optional.of(Component.literal("receiver"))),
                packet(301, SENDER, 8, null, "partly hidden", Instant.ofEpochMilli(2),
                        9, List.of(new MessageSignature.Packed(signature(12))), null,
                        partial, Optional.empty()),
                packet(302, SENDER, 9, signature(13), "hidden", Instant.ofEpochMilli(3),
                        10, List.of(), null, FilterMask.FULLY_FILTERED,
                        Optional.of(Component.literal("receiver"))));

        PacketRewriteResult unsignedResult = definition.handlePlayback(
                frame(nativePayload(sourcePackets.get(0))), descriptor(), codec, rewriter());
        assertEquals(PacketRewriteResult.Decision.SEND, unsignedResult.decision());
        assertEquals(Optional.of(Id033DisguisedChatPacketDefinition.PACKET_ID),
                unsignedResult.outputPacketId());
        ClientboundDisguisedChatPacket unsignedPresentation = assertInstanceOf(
                ClientboundDisguisedChatPacket.class, unsignedResult.packet().orElseThrow());
        assertEquals(Component.literal("unsigned display"), unsignedPresentation.message());
        assertEquals(sourcePackets.get(0).chatType(), unsignedPresentation.chatType());

        PacketRewriteResult filteredResult = definition.handlePlayback(
                frame(nativePayload(sourcePackets.get(1))), descriptor(), codec, rewriter());
        ClientboundDisguisedChatPacket filteredPresentation = assertInstanceOf(
                ClientboundDisguisedChatPacket.class, filteredResult.packet().orElseThrow());
        assertEquals(partial.applyWithFormatting("partly hidden"), filteredPresentation.message());
        assertEquals(sourcePackets.get(1).chatType(), filteredPresentation.chatType());
        assertEquals(Optional.of(Id033DisguisedChatPacketDefinition.PACKET_ID),
                filteredResult.outputPacketId());

        PacketRewriteResult fullyFilteredResult = definition.handlePlayback(
                frame(nativePayload(sourcePackets.get(2))), descriptor(), codec, rewriter());
        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, fullyFilteredResult.decision());
        assertTrue(fullyFilteredResult.packet().isEmpty());
        assertTrue(fullyFilteredResult.outputPacketId().isEmpty());

        assertEquals(3, decodes.get());
        assertEquals(0, encodes.get());
        assertThrows(RuntimeException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(), codec, rewriter()));

        var outputDescriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND,
                        Id033DisguisedChatPacketDefinition.PACKET_ID)
                .orElseThrow();
        var outputDefinition = Paper26_2PacketCatalog.catalog()
                .require(outputDescriptor.typeName());
        var encoded = outputDefinition.encode(
                unsignedPresentation, outputDescriptor, codec);
        assertEquals(Id033DisguisedChatPacketDefinition.PACKET_ID, encoded.packetId());
        assertEquals(1, encodes.get());
    }

    @Test
    void rejectsNullArgumentsAndDescriptorMismatchesBeforeApplyingLocalPolicy() {
        var definition = new Id065PlayerChatPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        RawPacketFrame frame = frame(new byte[0]);

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame, null, codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame, descriptor(), null, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame, descriptor(), codec, null));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame, descriptor(PACKET_ID + 1, PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame, descriptor(PACKET_ID, PacketDescriptor.PlaybackScope.VIEWER_LOCAL), codec, rewriter()));
    }

    @Test
    void nativeProtocolRoundTripsAllSignedBodySignatureFilterAndTargetForms() {
        ProtocolInfo<?> protocol = protocol(RegistryAccess.EMPTY);
        FilterMask partial = partialMask(0, 4, 255);
        List<MessageSignature.Packed> lastSeen = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            lastSeen.add(index % 2 == 0
                    ? new MessageSignature.Packed(index)
                    : new MessageSignature.Packed(signature(index)));
        }

        List<ClientboundPlayerChatPacket> packets = List.of(
                packet(Integer.MIN_VALUE, SENDER, Integer.MIN_VALUE, null, "", Instant.EPOCH,
                        Long.MIN_VALUE, List.of(), null, FilterMask.PASS_THROUGH, Optional.empty()),
                packet(300, SENDER, Integer.MAX_VALUE, signature(99), "x".repeat(256),
                        Instant.ofEpochMilli(1_700_000_000_123L), Long.MAX_VALUE,
                        lastSeen, Component.literal("filtered display")
                                .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))
                                .append(Component.literal(" component")),
                        partial, Optional.of(Component.translatableWithFallback(
                                "chat.type.text", "<%s> %s", "Receiver"))),
                packet(17, SENDER, 1, signature(4), "fully filtered", Instant.ofEpochMilli(-1),
                        0, List.of(new MessageSignature.Packed(0),
                                new MessageSignature.Packed(signature(5))),
                        null, FilterMask.FULLY_FILTERED, Optional.empty()));

        for (ClientboundPlayerChatPacket expected : packets) {
            assertRoundTrip(protocol, expected);
        }
    }

    @Test
    void nativeProtocolRoundTripsRegistryBackedChatDecorations() {
        MappedRegistry<ChatType> chatTypes = new MappedRegistry<>(
                Registries.CHAT_TYPE, Lifecycle.stable(), false);
        ResourceKey<ChatType> chatKey = ResourceKey.create(
                Registries.CHAT_TYPE, Identifier.withDefaultNamespace("replay_player_chat"));
        Holder.Reference<ChatType> registeredType = chatTypes.register(
                chatKey, customChatType(), RegistrationInfo.BUILT_IN);
        chatTypes.freeze();
        ProtocolInfo<?> protocol = protocol(
                new RegistryAccess.ImmutableRegistryAccess(List.of(chatTypes)));

        ChatType.Bound bound = new ChatType.Bound(
                registeredType,
                Component.literal("Sender").withStyle(Style.EMPTY.withItalic(true)),
                Optional.of(Component.literal("Recipient")));
        ClientboundPlayerChatPacket expected = new ClientboundPlayerChatPacket(
                101, SENDER, 12, signature(27),
                new SignedMessageBody.Packed(
                        "hello registry", Instant.ofEpochMilli(2026), 42,
                        new LastSeenMessages.Packed(List.of(new MessageSignature.Packed(3)))),
                null, partialMask(2), bound);

        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            assertEquals(PACKET_ID, readVarInt(encoded.duplicate()));
            ClientboundPlayerChatPacket decoded = assertInstanceOf(
                    ClientboundPlayerChatPacket.class, decode(protocol, encoded));
            assertEquals(expected, decoded);
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeCodecWritesPacketIdAndFieldsInProtocolOrder() {
        ProtocolInfo<?> protocol = protocol(RegistryAccess.EMPTY);
        UUID sender = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        ClientboundPlayerChatPacket packet = packet(
                300, sender, 17, null, "wire", Instant.ofEpochMilli(1234),
                0x0102030405060708L, List.of(), null,
                FilterMask.PASS_THROUGH, Optional.empty());
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            assertEquals(300, readVarInt(wire));
            assertEquals(sender.getMostSignificantBits(), wire.readLong());
            assertEquals(sender.getLeastSignificantBits(), wire.readLong());
            assertEquals(17, readVarInt(wire));
            assertFalse(wire.readBoolean(), "the nullable signature follows the sender index");
            int contentLength = readVarInt(wire);
            byte[] content = new byte[contentLength];
            wire.readBytes(content);
            assertEquals("wire", new String(content, StandardCharsets.UTF_8));
            assertTrue(wire.isReadable(), "timestamp, salt, acknowledgements and decorations follow the body text");

            ClientboundPlayerChatPacket decoded = assertInstanceOf(
                    ClientboundPlayerChatPacket.class, decode(protocol, encoded));
            assertEquals(packet, decoded);
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedBodyPrefixAndInvalidLastSeenCount() {
        ProtocolInfo<?> protocol = protocol(RegistryAccess.EMPTY);
        ClientboundPlayerChatPacket packet = packet(
                1, SENDER, 2, signature(1), "truncation", Instant.ofEpochMilli(123), 456,
                List.of(new MessageSignature.Packed(0),
                        new MessageSignature.Packed(signature(2))),
                Component.literal("unsigned"), partialMask(1),
                Optional.of(Component.literal("target")));
        byte[] complete = nativePayload(packet);
        assertTrue(complete.length > 0);

        for (int length = 0; length < complete.length; length++) {
            assertMalformed(protocol, Arrays.copyOf(complete, length));
        }

        List<MessageSignature.Packed> tooManySeen = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            tooManySeen.add(new MessageSignature.Packed(index));
        }
        ByteBuf invalid = encodePacket(protocol, packet(
                1, SENDER, 2, null, "invalid acknowledgements", Instant.EPOCH, 0,
                tooManySeen, null, FilterMask.PASS_THROUGH, Optional.empty()));
        try {
            assertThrows(RuntimeException.class, () -> decode(protocol, invalid));
        } finally {
            invalid.release();
        }
    }

    @Test
    void nativeDecoderLeavesTheNextPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol(RegistryAccess.EMPTY);
        ClientboundPlayerChatPacket packet = packet(
                15, SENDER, 3, signature(7), "next packet", Instant.EPOCH, 1,
                List.of(), null, FilterMask.PASS_THROUGH, Optional.empty());
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            encoded.writeByte(0x7A);
            assertEquals(packet, decode(protocol, encoded));
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void captureContextUsesUnsignedDisplayOrFilteredSignedBodySafely() {
        PaperConnectionAccessor accessor = new PaperConnectionAccessor();
        PacketDescriptor descriptor = descriptor();

        ClientboundPlayerChatPacket unsigned = packet(
                1, SENDER, 1, null, "signed source", Instant.EPOCH, 0, List.of(),
                Component.literal("display text"), FilterMask.PASS_THROUGH, Optional.empty());
        assertEquals(Optional.of("display text"),
                accessor.captureContext(unsigned, descriptor, Optional.empty()).normalizedChat());

        ClientboundPlayerChatPacket passThrough = packet(
                2, SENDER, 2, null, "signed source", Instant.EPOCH, 0, List.of(),
                null, FilterMask.PASS_THROUGH, Optional.empty());
        assertEquals(Optional.of("signed source"),
                accessor.captureContext(passThrough, descriptor, Optional.empty()).normalizedChat());

        FilterMask partial = partialMask(0, 4);
        ClientboundPlayerChatPacket filtered = packet(
                3, SENDER, 3, null, "signed source", Instant.EPOCH, 0, List.of(),
                null, partial, Optional.empty());
        assertEquals(Optional.of(partial.apply("signed source")),
                accessor.captureContext(filtered, descriptor, Optional.empty()).normalizedChat());

        ClientboundPlayerChatPacket hidden = packet(
                4, SENDER, 4, null, "filtered", Instant.EPOCH, 0, List.of(),
                null, FilterMask.FULLY_FILTERED, Optional.empty());
        assertEquals(Optional.of(""),
                accessor.captureContext(hidden, descriptor, Optional.empty()).normalizedChat());

        ClientboundPlayerChatPacket hiddenWithUnsignedText = packet(
                5, SENDER, 5, null, "filtered", Instant.EPOCH, 0, List.of(),
                Component.literal("must stay hidden"), FilterMask.FULLY_FILTERED, Optional.empty());
        assertEquals(Optional.of(""), accessor.captureContext(
                hiddenWithUnsignedText, descriptor, Optional.empty()).normalizedChat());
    }

    private static ClientboundPlayerChatPacket packet(
            int globalIndex,
            UUID sender,
            int index,
            MessageSignature signature,
            String content,
            Instant timestamp,
            long salt,
            List<MessageSignature.Packed> lastSeen,
            Component unsignedContent,
            FilterMask filterMask,
            Optional<Component> targetName) {
        return new ClientboundPlayerChatPacket(
                globalIndex,
                sender,
                index,
                signature,
                new SignedMessageBody.Packed(
                        content,
                        timestamp,
                        salt,
                        new LastSeenMessages.Packed(lastSeen)),
                unsignedContent,
                filterMask,
                new ChatType.Bound(
                        Holder.direct(customChatType()),
                        Component.literal("Sender"),
                        targetName));
    }

    private static FilterMask partialMask(int... filteredIndices) {
        FilterMask mask = new FilterMask(256);
        for (int index : filteredIndices) {
            mask.setFiltered(index);
        }
        return mask;
    }

    private static MessageSignature signature(int seed) {
        byte[] bytes = new byte[MessageSignature.BYTES];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (seed + index);
        }
        return new MessageSignature(bytes);
    }

    private static ChatType customChatType() {
        return new ChatType(
                new ChatTypeDecoration(
                        "replay.player_chat",
                        List.of(
                                ChatTypeDecoration.Parameter.SENDER,
                                ChatTypeDecoration.Parameter.TARGET,
                                ChatTypeDecoration.Parameter.CONTENT),
                        Style.EMPTY.withColor(ChatFormatting.GOLD)),
                new ChatTypeDecoration(
                        "replay.player_chat.narration",
                        List.of(ChatTypeDecoration.Parameter.CONTENT),
                        Style.EMPTY));
    }

    private static void assertRoundTrip(
            ProtocolInfo<?> protocol,
            ClientboundPlayerChatPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] expectedWire = bytes(encoded);
            ByteBuf header = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(header));
            ClientboundPlayerChatPacket decoded = assertInstanceOf(
                    ClientboundPlayerChatPacket.class, decode(protocol, encoded));
            assertEquals(expected, decoded);
            assertEquals(expected.body(), decoded.body());
            assertEquals(expected.signature(), decoded.signature());
            assertEquals(expected.filterMask(), decoded.filterMask());
            assertEquals(expected.chatType(), decoded.chatType());
            assertFalse(encoded.isReadable());

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

    private static byte[] nativePayload(ClientboundPlayerChatPacket packet) {
        ByteBuf encoded = encodePacket(protocol(RegistryAccess.EMPTY), packet);
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            byte[] payload = new byte[wire.readableBytes()];
            wire.readBytes(payload);
            return payload;
        } finally {
            encoded.release();
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] result = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), result);
        return result;
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

    private static ProtocolInfo<?> protocol(RegistryAccess access) {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(access));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(PACKET_ID, PacketDescriptor.PlaybackScope.SAFE);
    }

    private static PacketDescriptor descriptor(
            int packetId,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.CONFIGURABLE,
                true,
                true,
                false,
                "paper-26.2/" + TYPE_NAME,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local player chat must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local player chat must not decode");
            }
        };
    }

    private static PacketCodec<Object> nativeCodec(
            ProtocolInfo<?> protocol,
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                if (!(packet instanceof Packet<?> nativePacket)) {
                    throw new AssertionError("native packet codec received a non-packet value");
                }
                ByteBuf wire = encodePacket(protocol, nativePacket);
                try {
                    int packetId = readVarInt(wire);
                    byte[] payload = new byte[wire.readableBytes()];
                    wire.readBytes(payload);
                    return new EncodedPacket(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            packetId,
                            payload);
                } finally {
                    wire.release();
                }
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                ByteBuf wire = Unpooled.buffer();
                try {
                    writeVarInt(wire, frame.packetId());
                    wire.writeBytes(frame.payload());
                    return Id065PlayerChatPacketDefinitionTest.decode(protocol, wire);
                } finally {
                    wire.release();
                }
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
