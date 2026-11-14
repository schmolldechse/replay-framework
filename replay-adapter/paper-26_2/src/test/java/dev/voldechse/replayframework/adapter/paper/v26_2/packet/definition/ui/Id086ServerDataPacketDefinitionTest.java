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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import net.minecraft.network.protocol.game.ClientboundServerDataPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id086ServerDataPacketDefinitionTest {

    private static final int PACKET_ID = 86;
    private static final String TYPE_NAME = "play.clientbound.minecraft:server_data";
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
    void classifiesServerPresentationAsStatefulViewerLocalConnectionData() {
        var definition = new Id086ServerDataPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x56, Id086ServerDataPacketDefinition.PACKET_ID);
        assertEquals("minecraft:server_data", Id086ServerDataPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
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
        assertInstanceOf(Id086ServerDataPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id086ServerDataPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void nativeProtocolRoundTripsRichMotdsAndEveryIconPresenceShape() {
        ProtocolInfo<?> protocol = protocol();
        List<Component> motds = List.of(
                Component.empty(),
                Component.literal("A server message: Grüße, 雪, 😀"),
                Component.translatable(
                        "multiplayer.status.finished", Component.literal("Replay server")),
                Component.literal("Replay ")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true))
                        .append(Component.translatable("multiplayer.status.and_more")
                                .withStyle(Style.EMPTY.withItalic(true))));
        List<Optional<byte[]>> icons = new ArrayList<>();
        icons.add(Optional.empty());
        icons.add(Optional.of(new byte[0]));
        for (int length : new int[] {1, 127, 128, 255, 256, 1024}) {
            icons.add(Optional.of(iconBytes(length)));
        }

        for (Component motd : motds) {
            for (Optional<byte[]> icon : icons) {
                assertNativeRoundTrip(protocol, new ClientboundServerDataPacket(motd, icon));
            }
        }
    }

    @Test
    void nativeProtocolEncodesMotdBeforeOptionalIconFlagAndBytes() {
        ProtocolInfo<?> protocol = protocol();
        Component expectedMotd = Component.literal("Server").append(
                Component.literal(" icon").withStyle(Style.EMPTY.withBold(true)));
        byte[] expectedIcon = iconBytes(128);
        ClientboundServerDataPacket expected = new ClientboundServerDataPacket(
                expectedMotd, Optional.of(expectedIcon));
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(expectedMotd,
                    ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(wire));
            assertEquals(1, wire.readUnsignedByte());
            assertEquals(expectedIcon.length, wire.readVarInt());
            byte[] icon = new byte[expectedIcon.length];
            wire.readBytes(icon);
            assertArrayEquals(expectedIcon, icon);
            assertFalse(wire.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeProtocolTreatsAnyNonzeroOptionalFlagAsPresentAndCanonicalizesIt() {
        ProtocolInfo<?> protocol = protocol();
        Component expectedMotd = Component.translatable("server.replay.motd");
        byte[] expectedIcon = iconBytes(3);
        FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VarInt.write(input, PACKET_ID);
            ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(input, expectedMotd);
            input.writeByte(0x80);
            input.writeVarInt(expectedIcon.length);
            input.writeBytes(expectedIcon);

            ClientboundServerDataPacket decoded = assertInstanceOf(
                    ClientboundServerDataPacket.class, decode(protocol, input));
            assertEquals(expectedMotd, decoded.motd());
            assertArrayEquals(expectedIcon, decoded.iconBytes().orElseThrow());
            assertFalse(input.isReadable());

            ByteBuf canonical = encodePacket(protocol, decoded);
            try {
                FriendlyByteBuf wire = new FriendlyByteBuf(canonical.duplicate());
                assertEquals(PACKET_ID, VarInt.read(wire));
                ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(wire);
                assertEquals(1, wire.readUnsignedByte());
                assertEquals(expectedIcon.length, wire.readVarInt());
                byte[] canonicalIcon = new byte[expectedIcon.length];
                wire.readBytes(canonicalIcon);
                assertArrayEquals(expectedIcon, canonicalIcon);
                assertFalse(wire.isReadable());
            } finally {
                canonical.release();
            }
        } finally {
            input.release();
        }
    }

    @Test
    void dropsAllServerDataVariantsAndMalformedPayloadsBeforeCodecAccess() {
        var definition = new Id086ServerDataPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<byte[]> payloads = new ArrayList<>();
        for (Component motd : List.of(
                Component.empty(),
                Component.literal("Recorded server"),
                Component.translatable("server.replay.motd"))) {
            payloads.add(nativePayload(new ClientboundServerDataPacket(motd, Optional.empty())));
            payloads.add(nativePayload(new ClientboundServerDataPacket(
                    motd, Optional.of(new byte[0]))));
            payloads.add(nativePayload(new ClientboundServerDataPacket(
                    motd, Optional.of(iconBytes(256)))));
        }
        payloads.add(new byte[0]);
        payloads.add(new byte[] {(byte) 0x80});
        payloads.add(new byte[] {0});
        payloads.add(new byte[] {1});

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
        PacketRewriteResult result = new Id086ServerDataPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id086ServerDataPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(frame(new byte[0]), mismatch, codec, rewriter()));
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPacketBodyPrefixAndInvalidOptionalIconLengths() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol, new ClientboundServerDataPacket(
                Component.translatable("server.replay.motd"), Optional.of(iconBytes(128))));
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

        for (int invalidLength : new int[] {-1, Integer.MAX_VALUE}) {
            FriendlyByteBuf malformed = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(
                        malformed, Component.literal("MOTD"));
                malformed.writeBoolean(true);
                VarInt.write(malformed, invalidLength);
                assertMalformed(protocol, bytes(malformed));
            } finally {
                malformed.release();
            }
        }

        FriendlyByteBuf truncatedIcon = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(
                    truncatedIcon, Component.literal("MOTD"));
            truncatedIcon.writeBoolean(true);
            truncatedIcon.writeVarInt(4);
            truncatedIcon.writeBytes(new byte[] {1, 2, 3});
            assertMalformed(protocol, bytes(truncatedIcon));
        } finally {
            truncatedIcon.release();
        }
    }

    @Test
    void nativeProtocolLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundServerDataPacket packet = new ClientboundServerDataPacket(
                Component.empty(), Optional.empty());
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            encoded.writeByte(0x7A);
            ClientboundServerDataPacket decoded = assertInstanceOf(
                    ClientboundServerDataPacket.class, decode(protocol, encoded));
            assertEquals(Component.empty(), decoded.motd());
            assertTrue(decoded.iconBytes().isEmpty());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundServerDataPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] originalWire = bytes(encoded);
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            Component wireMotd =
                    ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(wire);
            assertEquals(expected.iconBytes().isPresent(), wire.readBoolean());
            if (expected.iconBytes().isPresent()) {
                byte[] expectedIcon = expected.iconBytes().orElseThrow();
                assertEquals(expectedIcon.length, wire.readVarInt());
                byte[] icon = new byte[expectedIcon.length];
                wire.readBytes(icon);
                assertArrayEquals(expectedIcon, icon);
            }
            assertFalse(wire.isReadable());

            ClientboundServerDataPacket decoded = assertInstanceOf(
                    ClientboundServerDataPacket.class, decode(protocol, encoded));
            // The network component codec normalizes literal translatable arguments to strings.
            // Compare the packet decoder to the exact component representation read from its wire.
            assertEquals(wireMotd, decoded.motd());
            assertEquals(expected.iconBytes().isPresent(), decoded.iconBytes().isPresent());
            if (expected.iconBytes().isPresent()) {
                assertArrayEquals(
                        expected.iconBytes().orElseThrow(), decoded.iconBytes().orElseThrow());
            }
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

    private static byte[] iconBytes(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (index * 31 + 7);
        }
        return bytes;
    }

    private static byte[] nativePayload(ClientboundServerDataPacket packet) {
        ByteBuf encoded = encodePacket(protocol(), packet);
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
                PacketDisposition.STATEFUL,
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
