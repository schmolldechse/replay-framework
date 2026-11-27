package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.datafixers.util.Either;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.Paper26_2ClientboundPacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
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
import net.minecraft.network.chat.Style;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.ServerLinks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id137ServerLinksPacketDefinitionTest {

    private static final int PACKET_ID = 137;
    private static final String TYPE_NAME = "play.clientbound.minecraft:server_links";
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
    void classifiesServerLinksAsViewerLocalPauseMenuState() {
        var definition = new Id137ServerLinksPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x89, Id137ServerLinksPacketDefinition.PACKET_ID);
        assertEquals("minecraft:server_links", Id137ServerLinksPacketDefinition.IDENTIFIER);
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
        var catalogMatches = Paper26_2ClientboundPacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id137ServerLinksPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id137ServerLinksPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
        assertTrue(Paper26_2ClientboundPacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void nativeProtocolRoundTripsEmptyKnownAndCustomLinkLists() {
        ProtocolInfo<?> protocol = protocol();
        List<ServerLinks.UntrustedEntry> links = new ArrayList<>();
        for (ServerLinks.KnownLinkType type : ServerLinks.KnownLinkType.values()) {
            links.add(known(type, "https://example.test/" + type.name().toLowerCase()));
        }
        links.add(custom(
                Component.literal("Community")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true)),
                "https://example.test/community?from=replay&lang=de"));
        links.add(custom(
                Component.translatable("server.links.custom", Component.literal("Updates")),
                "mailto:updates@example.test"));
        links.add(custom(Component.empty(), ""));
        links.add(custom(Component.literal("Unicode 雪 😀"), "https://例え.テスト/道"));

        assertNativeRoundTrip(protocol, List.of());
        assertNativeRoundTrip(protocol, links);
    }

    @Test
    void nativeProtocolUsesBuiltInOrCustomComponentDiscriminatorAndVarIntEnumIds() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol, newPacket(List.of(
                known(ServerLinks.KnownLinkType.ANNOUNCEMENTS, "https://example.test/news"),
                custom(Component.literal("My server"), "https://example.test"))));
        try {
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(2, VarInt.read(wire));

            assertTrue(wire.readBoolean(), "true selects the built-in enum label");
            assertEquals(
                    ServerLinks.KnownLinkType.ANNOUNCEMENTS,
                    ServerLinks.KnownLinkType.STREAM_CODEC.decode(wire));
            assertEquals("https://example.test/news", ByteBufCodecs.STRING_UTF8.decode(wire));

            assertFalse(wire.readBoolean(), "false selects a custom text component");
            Component customLabel = net.minecraft.network.chat.ComponentSerialization
                    .TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(wire);
            assertEquals("My server", customLabel.getString());
            assertEquals("https://example.test", ByteBufCodecs.STRING_UTF8.decode(wire));
            assertFalse(wire.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeProtocolMapsUnknownBuiltInLabelIdsToTheNativeFirstEntryFallback() {
        FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VarInt.write(input, PACKET_ID);
            VarInt.write(input, 1);
            input.writeBoolean(true);
            VarInt.write(input, 10);
            ByteBufCodecs.STRING_UTF8.encode(input, "https://example.test/unknown-label");

            var decoded = assertInstanceOf(
                    net.minecraft.network.protocol.common.ClientboundServerLinksPacket.class,
                    decode(protocol(), input));
            assertEquals(1, decoded.links().size());
            assertEquals(
                    Either.left(ServerLinks.KnownLinkType.BUG_REPORT),
                    decoded.links().getFirst().type());
            assertEquals("https://example.test/unknown-label", decoded.links().getFirst().link());
            assertFalse(input.isReadable());
        } finally {
            input.release();
        }
    }

    @Test
    void nativeProtocolEnforcesTheMaximumLinkStringLength() {
        ProtocolInfo<?> protocol = protocol();
        String maximum = "x".repeat(32767);
        ByteBuf encoded = encodePacket(protocol, newPacket(List.of(
                known(ServerLinks.KnownLinkType.WEBSITE, maximum))));
        try {
            var decoded = assertInstanceOf(
                    net.minecraft.network.protocol.common.ClientboundServerLinksPacket.class,
                    decode(protocol, encoded));
            assertEquals(maximum, decoded.links().getFirst().link());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }

        ByteBuf tooLong = Unpooled.buffer();
        try {
            assertThrows(RuntimeException.class, () -> encode(
                    protocol,
                    tooLong,
                    newPacket(List.of(known(
                            ServerLinks.KnownLinkType.WEBSITE, "x".repeat(32768))))));
        } finally {
            tooLong.release();
        }
    }

    @Test
    void dropsRecordedLinksAndMalformedPayloadsBeforePlaybackCodecAccess() {
        var definition = new Id137ServerLinksPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<byte[]> payloads = new ArrayList<>();
        payloads.add(nativePayload(List.of()));
        payloads.add(nativePayload(List.of(
                known(ServerLinks.KnownLinkType.SUPPORT, "https://example.test/help"),
                custom(Component.literal("Contact"), "mailto:help@example.test"))));
        payloads.add(new byte[0]);
        payloads.add(new byte[] {(byte) 0x80});
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
        PacketRewriteResult result = new Id137ServerLinksPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id137ServerLinksPacketDefinition();
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
    void nativeProtocolRejectsEveryTruncatedPacketBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol, newPacket(List.of(
                custom(Component.literal("Community"), "https://example.test/community"),
                known(ServerLinks.KnownLinkType.FEEDBACK, "https://example.test/feedback"))));
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
    }

    @Test
    void nativeProtocolLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol, newPacket(List.of(
                known(ServerLinks.KnownLinkType.STATUS, "https://status.example.test"))));
        try {
            encoded.writeByte(0x7A);
            var decoded = assertInstanceOf(
                    net.minecraft.network.protocol.common.ClientboundServerLinksPacket.class,
                    decode(protocol, encoded));
            assertEquals(1, decoded.links().size());
            assertEquals("https://status.example.test", decoded.links().getFirst().link());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, List<ServerLinks.UntrustedEntry> links) {
        ByteBuf encoded = encodePacket(protocol, newPacket(links));
        try {
            byte[] originalWire = bytes(encoded);
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            List<ServerLinks.UntrustedEntry> wireLinks =
                    ServerLinks.UNTRUSTED_LINKS_STREAM_CODEC.decode(wire);
            assertFalse(wire.isReadable());

            var decoded = assertInstanceOf(
                    net.minecraft.network.protocol.common.ClientboundServerLinksPacket.class,
                    decode(protocol, encoded));
            // Native text-component decoding may normalize literal translation arguments.
            // Compare both packet decoding paths with the exact representation on the wire.
            assertEquals(wireLinks, decoded.links());
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

    private static ServerLinks.UntrustedEntry known(ServerLinks.KnownLinkType type, String link) {
        return new ServerLinks.UntrustedEntry(Either.left(type), link);
    }

    private static ServerLinks.UntrustedEntry custom(Component label, String link) {
        return new ServerLinks.UntrustedEntry(Either.right(label), link);
    }

    private static net.minecraft.network.protocol.common.ClientboundServerLinksPacket newPacket(
            List<ServerLinks.UntrustedEntry> links) {
        return new net.minecraft.network.protocol.common.ClientboundServerLinksPacket(links);
    }

    private static byte[] nativePayload(List<ServerLinks.UntrustedEntry> links) {
        ByteBuf encoded = encodePacket(protocol(), newPacket(links));
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
