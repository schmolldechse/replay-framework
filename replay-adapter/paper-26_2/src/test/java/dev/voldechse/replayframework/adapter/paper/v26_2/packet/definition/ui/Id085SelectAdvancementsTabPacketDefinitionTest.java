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
import net.minecraft.network.protocol.game.ClientboundSelectAdvancementsTabPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id085SelectAdvancementsTabPacketDefinitionTest {

    private static final int PACKET_ID = 85;
    private static final String TYPE_NAME = "play.clientbound.minecraft:select_advancements_tab";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final List<Identifier> BUILT_IN_TABS = List.of(
            Identifier.parse("minecraft:story/root"),
            Identifier.parse("minecraft:nether/root"),
            Identifier.parse("minecraft:end/root"),
            Identifier.parse("minecraft:adventure/root"),
            Identifier.parse("minecraft:husbandry/root"));

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesTabSelectionAsEphemeralViewerLocalUiState() {
        var definition = new Id085SelectAdvancementsTabPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x55, Id085SelectAdvancementsTabPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:select_advancements_tab",
                Id085SelectAdvancementsTabPacketDefinition.IDENTIFIER);
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
        assertInstanceOf(Id085SelectAdvancementsTabPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id085SelectAdvancementsTabPacketDefinition.class, catalogMatches.getFirst());

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
    void nativeProtocolRoundTripsAbsentBuiltInCustomAndUnknownValidTabs() {
        ProtocolInfo<?> protocol = protocol();
        List<Identifier> tabs = new ArrayList<>();
        tabs.add(null);
        tabs.addAll(BUILT_IN_TABS);
        tabs.add(Identifier.parse(""));
        tabs.add(Identifier.parse("custom_pack:story/extra_root"));
        tabs.add(Identifier.parse("unknown_pack:advancement/not_loaded"));

        for (Identifier tab : tabs) {
            assertNativeRoundTrip(protocol, packet(tab));
        }
    }

    @Test
    void nativeProtocolUsesAFalseOrTruePresenceFlagBeforeTheIdentifier() {
        ProtocolInfo<?> protocol = protocol();

        for (Identifier tab : new Identifier[] {null, Identifier.parse("minecraft:story/root")}) {
            ByteBuf encoded = encodePacket(protocol, packet(tab));
            try {
                byte[] originalWire = bytes(encoded);
                FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
                assertEquals(PACKET_ID, VarInt.read(wire));
                assertEquals(tab != null, wire.readBoolean());
                if (tab != null) {
                    assertEquals(tab, wire.readIdentifier());
                }
                assertFalse(wire.isReadable());

                ClientboundSelectAdvancementsTabPacket decoded = assertInstanceOf(
                        ClientboundSelectAdvancementsTabPacket.class, decode(protocol, encoded));
                assertEquals(tab, decoded.getTab());
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
    }

    @Test
    void nativeProtocolInterpretsNonzeroPresenceAsPresentAndReencodesCanonically() {
        ProtocolInfo<?> protocol = protocol();
        Identifier expectedTab = Identifier.parse("custom_pack:story/root");
        FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VarInt.write(input, PACKET_ID);
            input.writeByte(0x80);
            input.writeUtf(expectedTab.toString());

            ClientboundSelectAdvancementsTabPacket decoded = assertInstanceOf(
                    ClientboundSelectAdvancementsTabPacket.class, decode(protocol, input));
            assertEquals(expectedTab, decoded.getTab());
            assertFalse(input.isReadable());

            ByteBuf canonical = encodePacket(protocol, decoded);
            try {
                FriendlyByteBuf wire = new FriendlyByteBuf(canonical.duplicate());
                assertEquals(PACKET_ID, VarInt.read(wire));
                assertEquals(1, wire.readUnsignedByte());
                assertEquals(expectedTab, wire.readIdentifier());
                assertFalse(wire.isReadable());
            } finally {
                canonical.release();
            }
        } finally {
            input.release();
        }
    }

    @Test
    void dropsEveryTabVariantAndMalformedPayloadBeforeCodecAccess() {
        var definition = new Id085SelectAdvancementsTabPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<byte[]> payloads = new ArrayList<>();
        payloads.add(nativePayload(packet(null)));
        for (Identifier tab : BUILT_IN_TABS) {
            payloads.add(nativePayload(packet(tab)));
        }
        payloads.add(nativePayload(packet(Identifier.parse(""))));
        payloads.add(nativePayload(packet(Identifier.parse("custom_pack:story/extra_root"))));
        payloads.add(nativePayload(packet(Identifier.parse("unknown_pack:missing/root"))));
        payloads.add(new byte[0]);
        payloads.add(new byte[] {1});
        payloads.add(new byte[] {1, 0});
        payloads.add(new byte[] {1, (byte) 0x80});
        payloads.add(new byte[] {(byte) 0xFF, (byte) 0x80, 0});

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
        PacketRewriteResult result = new Id085SelectAdvancementsTabPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id085SelectAdvancementsTabPacketDefinition();
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
    void nativeProtocolRejectsEveryTruncatedPacketBodyPrefixAndInvalidIdentifiers() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(
                protocol, packet(Identifier.parse("minecraft:story/root")));
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

        for (String invalidIdentifier : List.of(
                "minecraft:UpperCase", "namespace:bad space", "a:b:c", "namespace:bad@path")) {
            FriendlyByteBuf malformed = new FriendlyByteBuf(Unpooled.buffer());
            try {
                malformed.writeBoolean(true);
                malformed.writeUtf(invalidIdentifier);
                assertMalformed(protocol, bytes(malformed));
            } finally {
                malformed.release();
            }
        }
    }

    @Test
    void nativeProtocolLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol, packet(null));
        try {
            encoded.writeByte(0x7A);
            ClientboundSelectAdvancementsTabPacket decoded = assertInstanceOf(
                    ClientboundSelectAdvancementsTabPacket.class, decode(protocol, encoded));
            assertEquals(null, decoded.getTab());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static ClientboundSelectAdvancementsTabPacket packet(Identifier tab) {
        return new ClientboundSelectAdvancementsTabPacket(tab);
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundSelectAdvancementsTabPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] originalWire = bytes(encoded);
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(expected.getTab() != null, wire.readBoolean());
            if (expected.getTab() != null) {
                assertEquals(expected.getTab(), wire.readIdentifier());
            }
            assertFalse(wire.isReadable());

            ClientboundSelectAdvancementsTabPacket decoded = assertInstanceOf(
                    ClientboundSelectAdvancementsTabPacket.class, decode(protocol, encoded));
            assertEquals(expected.getTab(), decoded.getTab());
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

    private static byte[] nativePayload(ClientboundSelectAdvancementsTabPacket packet) {
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
