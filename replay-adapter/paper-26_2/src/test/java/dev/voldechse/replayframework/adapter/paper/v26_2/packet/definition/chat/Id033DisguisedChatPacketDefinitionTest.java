package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
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
import net.minecraft.network.chat.Style;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id033DisguisedChatPacketDefinitionTest {

    private static final int PACKET_ID = 33;
    private static final String TYPE_NAME = "play.clientbound.minecraft:disguised_chat";
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
    void classifiesDisguisedChatAsViewerLocalEphemeralData() {
        var definition = new Id033DisguisedChatPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x21, Id033DisguisedChatPacketDefinition.PACKET_ID);
        assertEquals("minecraft:disguised_chat", Id033DisguisedChatPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());
    }

    @Test
    void registersExactlyOnceInChatDomainCatalogAndPaperRegistry() {
        var domainMatches = ChatPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id033DisguisedChatPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id033DisguisedChatPacketDefinition.class, catalogMatches.getFirst());

        var registryDescriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, registryDescriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, registryDescriptor.disposition());
        assertTrue(registryDescriptor.captureByDefault());
        assertTrue(registryDescriptor.replayable());
        assertFalse(registryDescriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, registryDescriptor.playbackScope());
    }

    @Test
    void dropsRecordedChatBeforeDecodingOrEncodingAnyPayload() {
        var definition = new Id033DisguisedChatPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {1},
            new byte[] {(byte) 0x80},
            new byte[] {10, 1, 2, 3}
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
    void directRewriteDropsRecordedChatWithoutInspectingThePacket() {
        PacketRewriteResult result = new Id033DisguisedChatPacketDefinition()
                .rewrite(new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void validatesNullAndDescriptorBoundariesBeforeApplyingLocalPolicy() {
        var definition = new Id033DisguisedChatPacketDefinition();
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
                frame(new byte[0]), descriptor(PACKET_ID + 1, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(PACKET_ID, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(PACKET_ID, true,
                        PacketDescriptor.PlaybackScope.SAFE), codec, rewriter()));
    }

    @Test
    void nativeProtocolRoundTripsComponentFamiliesTargetsAndInlineChatTypes() {
        ProtocolInfo<?> protocol = protocol(RegistryAccess.EMPTY);
        List<String> components = List.of(
                "\"\"",
                "\"Disguised: Grüße, 雪, 😀\"",
                "{\"text\":\"Chat\",\"color\":\"gold\",\"bold\":true,\"extra\":[{\"text\":\" now\",\"italic\":true}]}",
                "[\"First\",{\"text\":\" second\"}]",
                "{\"translate\":\"chat.type.text\",\"fallback\":\"<%s> %s\",\"with\":[\"Alice\",{\"text\":\"hello\"}]}",
                "{\"keybind\":\"key.jump\"}",
                "{\"score\":{\"name\":\"@s\",\"objective\":\"sidebar\"}}",
                "{\"selector\":\"@e[type=minecraft:zombie,limit=1]\",\"separator\":{\"text\":\", \"}}",
                "{\"nbt\":\"Items[].id\",\"plain\":true,\"storage\":\"minecraft:replay_chat\"}",
                "{\"nbt\":\"Pos[0]\",\"interpret\":true,\"block\":\"~ ~ ~\"}",
                "{\"nbt\":\"Health\",\"entity\":\"@s\"}",
                "{\"atlas\":\"minecraft:blocks\",\"sprite\":\"minecraft:stone\",\"fallback\":\"stone\"}",
                "{\"player\":{\"name\":\"Notch\"},\"hat\":false}");

        for (int index = 0; index < components.size(); index++) {
            Component message = decodeComponent(components.get(index));
            Component sender = decodeComponent(components.get(components.size() - index - 1));
            Component target = decodeComponent(components.get(index));
            ChatType.Bound chatType = new ChatType.Bound(
                    Holder.direct(customChatType()), sender, Optional.of(target));
            assertRoundTrip(protocol, new ClientboundDisguisedChatPacket(message, chatType));
        }

        // A missing target is a distinct valid form; the decoration references
        // all three parameters so the absent-target fallback is exercised too.
        ChatType.Bound withoutTarget = new ChatType.Bound(
                Holder.direct(customChatType()),
                Component.literal("Sender"),
                Optional.empty());
        assertRoundTrip(protocol, new ClientboundDisguisedChatPacket(
                Component.literal("a".repeat(32_767)), withoutTarget));
    }

    @Test
    void nativeProtocolRoundTripsRegistryBackedChatTypeAndOptionalTarget() {
        MappedRegistry<ChatType> chatTypes = new MappedRegistry<>(
                Registries.CHAT_TYPE, Lifecycle.stable(), false);
        ResourceKey<ChatType> chatKey = ResourceKey.create(
                Registries.CHAT_TYPE, Identifier.withDefaultNamespace("replay_test"));
        Holder.Reference<ChatType> registeredType = chatTypes.register(
                chatKey, customChatType(), RegistrationInfo.BUILT_IN);
        chatTypes.freeze();
        ProtocolInfo<?> protocol = protocol(new RegistryAccess.ImmutableRegistryAccess(List.of(chatTypes)));

        for (Optional<Component> target : List.<Optional<Component>>of(
                Optional.empty(), Optional.of(Component.literal("Receiver").withStyle(Style.EMPTY.withItalic(true))))) {
            ChatType.Bound chatType = new ChatType.Bound(
                    registeredType,
                    Component.literal("Alice"),
                    target);
            ClientboundDisguisedChatPacket packet = new ClientboundDisguisedChatPacket(
                    Component.translatableWithFallback(
                                    "replay.chat.message", "message: %s", "hello")
                            .append(Component.literal(" 🌍")),
                    chatType);
            assertRoundTrip(protocol, packet);
        }
    }

    @Test
    void nativeProtocolRejectsMissingAndEveryTruncatedPayloadPrefix() {
        ProtocolInfo<?> protocol = protocol(RegistryAccess.EMPTY);
        assertMalformed(protocol, new byte[0]);
        assertMalformed(protocol, new byte[] {0});
        assertMalformed(protocol, new byte[] {10});

        ClientboundDisguisedChatPacket packet = new ClientboundDisguisedChatPacket(
                Component.translatableWithFallback("chat.type.text", "<%s> %s", "Alice", "hello"),
                new ChatType.Bound(
                        Holder.direct(customChatType()),
                        Component.literal("Alice"),
                        Optional.of(Component.literal("Receiver"))));
        ByteBuf complete = encodePacket(protocol, packet);
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

    private static void assertRoundTrip(
            ProtocolInfo<?> protocol,
            ClientboundDisguisedChatPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            ByteBuf header = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(header));

            ClientboundDisguisedChatPacket decoded = assertInstanceOf(
                    ClientboundDisguisedChatPacket.class, decode(protocol, encoded));
            assertEquals(expected.message(), decoded.message());
            assertEquals(expected.chatType(), decoded.chatType());
            assertEquals(
                    expected.chatType().decorate(expected.message()),
                    decoded.chatType().decorate(decoded.message()));
            assertEquals(
                    expected.chatType().decorateNarration(expected.message()),
                    decoded.chatType().decorateNarration(decoded.message()));
            assertTrue(decoded.isSkippable());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static ChatType customChatType() {
        ChatTypeDecoration chat = new ChatTypeDecoration(
                "replay.chat.decorated",
                List.of(
                        ChatTypeDecoration.Parameter.SENDER,
                        ChatTypeDecoration.Parameter.TARGET,
                        ChatTypeDecoration.Parameter.CONTENT),
                Style.EMPTY.withColor(ChatFormatting.GOLD).withItalic(true));
        ChatTypeDecoration narration = new ChatTypeDecoration(
                "replay.chat.narration",
                List.of(ChatTypeDecoration.Parameter.CONTENT),
                Style.EMPTY);
        return new ChatType(chat, narration);
    }

    private static Component decodeComponent(String json) {
        return net.minecraft.network.chat.ComponentSerialization.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow();
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

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(PACKET_ID, true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
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
                PacketDisposition.EPHEMERAL,
                true,
                replayable,
                false,
                "paper-26.2/" + TYPE_NAME,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local disguised chat must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local disguised chat must not decode");
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
