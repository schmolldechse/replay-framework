package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

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
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
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
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Style;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id087SetActionBarTextPacketDefinitionTest {

    private static final int PACKET_ID = 87;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_action_bar_text";
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
    void classifiesActionBarMessageAsEphemeralViewerLocalUiEvent() {
        var definition = new Id087SetActionBarTextPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x57, Id087SetActionBarTextPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:set_action_bar_text",
                Id087SetActionBarTextPacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInUiDomainCatalogAndPaperRegistry() {
        var domainMatches = UiPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id087SetActionBarTextPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id087SetActionBarTextPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void nativeProtocolRoundTripsEveryRepresentativeTextComponentShape() {
        ProtocolInfo<?> protocol = protocol();
        List<Component> messages = List.of(
                Component.empty(),
                Component.literal("Action bar: Grüße, 雪, 😀"),
                Component.translatable("item.minecraft.diamond.name"),
                Component.translatable(
                        "multiplayer.status.finished", Component.literal("Replay server")),
                Component.literal("Warning ")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true))
                        .append(Component.translatable("chat.type.text", "Player", "hello")
                                .withStyle(Style.EMPTY.withItalic(true))));

        for (Component message : messages) {
            assertNativeRoundTrip(protocol, new ClientboundSetActionBarTextPacket(message));
        }
    }

    @Test
    void nativeProtocolEncodesExactlyOneTextComponentAfterThePacketId() {
        ProtocolInfo<?> protocol = protocol();
        Component expected = Component.literal("Danger!")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(true));
        ByteBuf encoded = encodePacket(protocol, new ClientboundSetActionBarTextPacket(expected));
        try {
            byte[] originalWire = bytes(encoded);
            RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(
                    encoded.duplicate(), RegistryAccess.fromRegistryOfRegistries(
                            BuiltInRegistries.REGISTRY));
            assertEquals(PACKET_ID, VarInt.read(wire));
            Component wireText = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(wire);
            assertEquals(expected, wireText);
            assertFalse(wire.isReadable());

            ClientboundSetActionBarTextPacket decoded = assertInstanceOf(
                    ClientboundSetActionBarTextPacket.class, decode(protocol, encoded));
            assertEquals(wireText, decoded.text());
            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(originalWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    @Test
    void dropsValidAndMalformedMessagesBeforeCodecAccess() {
        var definition = new Id087SetActionBarTextPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<byte[]> payloads = List.of(
                nativePayload(Component.empty()),
                nativePayload(Component.literal("Recorded action bar")),
                nativePayload(Component.translatable("server.replay.actionbar")),
                new byte[0],
                new byte[] {0},
                new byte[] {1},
                new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07});

        for (byte[] payload : payloads) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());
            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
            assertEquals(
                    "recorded connection state does not belong to the observer",
                    result.reason());
        }
        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingTheDecodedPacket() {
        PacketRewriteResult result = new Id087SetActionBarTextPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id087SetActionBarTextPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            frame(new byte[0]), mismatch, codec, rewriter()));
        }
    }

    @Test
    void nativeProtocolRejectsTruncatedAndMalformedTextComponents() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol, new ClientboundSetActionBarTextPacket(
                Component.translatable("server.replay.actionbar")));
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            byte[] body = bytes(wire);
            for (int length = 0; length < body.length; length++) {
                assertMalformed(protocol, java.util.Arrays.copyOf(body, length));
            }
        } finally {
            encoded.release();
        }

        FriendlyByteBuf invalidJson = new FriendlyByteBuf(Unpooled.buffer());
        try {
            invalidJson.writeUtf("{");
            assertMalformed(protocol, bytes(invalidJson));
        } finally {
            invalidJson.release();
        }

        FriendlyByteBuf invalidLength = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VarInt.write(invalidLength, Integer.MAX_VALUE);
            assertMalformed(protocol, bytes(invalidLength));
        } finally {
            invalidLength.release();
        }
    }

    @Test
    void nativeProtocolLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol,
                new ClientboundSetActionBarTextPacket(Component.literal("Notice")));
        try {
            encoded.writeByte(0x7A);
            ClientboundSetActionBarTextPacket decoded = assertInstanceOf(
                    ClientboundSetActionBarTextPacket.class, decode(protocol, encoded));
            assertEquals(Component.literal("Notice"), decoded.text());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundSetActionBarTextPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] originalWire = bytes(encoded);
            RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(
                    encoded.duplicate(), RegistryAccess.fromRegistryOfRegistries(
                            BuiltInRegistries.REGISTRY));
            assertEquals(PACKET_ID, VarInt.read(wire));
            Component wireText = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(wire);
            assertFalse(wire.isReadable());

            ClientboundSetActionBarTextPacket decoded = assertInstanceOf(
                    ClientboundSetActionBarTextPacket.class, decode(protocol, encoded));
            assertEquals(wireText, decoded.text());
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(originalWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static byte[] nativePayload(Component text) {
        ByteBuf encoded = encodePacket(protocol(), new ClientboundSetActionBarTextPacket(text));
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded));
            return bytes(encoded);
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

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
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
        return descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            String typeName, int packetId, PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                typeName,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
                "paper-26.2/" + typeName,
                scope);
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
                throw new AssertionError("viewer-local packet must not be encoded");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local packet must not be decoded");
            }
        };
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf input = Unpooled.buffer();
        try {
            VarInt.write(input, PACKET_ID);
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
}
