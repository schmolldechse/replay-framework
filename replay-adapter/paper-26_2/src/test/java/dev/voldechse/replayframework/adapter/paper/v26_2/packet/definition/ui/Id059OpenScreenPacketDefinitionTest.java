package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.inventory.MenuType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id059OpenScreenPacketDefinitionTest {

    private static final int PACKET_ID = 59;
    private static final String TYPE_NAME = "play.clientbound.minecraft:open_screen";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final int[] CONTAINER_IDS = {
        Integer.MIN_VALUE, -1, 0, 1, 127, 128, Integer.MAX_VALUE
    };

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesRecordedContainerUiAsViewerLocalEphemeralData() {
        var definition = new Id059OpenScreenPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x3B, Id059OpenScreenPacketDefinition.PACKET_ID);
        assertEquals("minecraft:open_screen", Id059OpenScreenPacketDefinition.IDENTIFIER);
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
        assertInstanceOf(Id059OpenScreenPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id059OpenScreenPacketDefinition.class, catalogMatches.getFirst());

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
    void dropsValidMenuPacketsAndArbitraryPayloadsBeforeCodecDecodeOrEncode() {
        var definition = new Id059OpenScreenPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<byte[]> payloads = List.of(
                new byte[0],
                nativePayload(packet(12, MenuType.GENERIC_9x3, richTitles().getFirst())),
                nativePayload(packet(0, MenuType.ANVIL, richTitles().get(1))),
                new byte[] {1},
                new byte[16],
                new byte[] {(byte) 0xFF, (byte) 0x80, 0});

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
        PacketRewriteResult result = new Id059OpenScreenPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingLocalPolicy() {
        var definition = new Id059OpenScreenPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            frame(new byte[0]), mismatch, codec, rewriter()));
        }
    }

    @Test
    void nativeProtocolRoundTripsEveryBuiltinMenuAndContainerIdWithRichTitles() {
        ProtocolInfo<?> protocol = protocol();
        List<MenuType<?>> menuTypes = new ArrayList<>();
        BuiltInRegistries.MENU.forEach(menuTypes::add);
        assertFalse(menuTypes.isEmpty());
        List<Component> titles = richTitles();

        for (MenuType<?> menuType : menuTypes) {
            for (int containerId : CONTAINER_IDS) {
                for (Component title : titles) {
                    assertNativeRoundTrip(protocol, packet(containerId, menuType, title));
                }
            }
        }
    }

    @Test
    void nativeProtocolRejectsMalformedContainerMenuAndTitleFields() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, new byte[0]);
        assertMalformed(protocol, new byte[] {(byte) 0x80});
        assertMalformed(protocol, new byte[] {
            (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0
        });

        ByteBuf unknownMenu = Unpooled.buffer();
        try {
            VarInt.write(unknownMenu, PACKET_ID);
            VarInt.write(unknownMenu, 4);
            VarInt.write(unknownMenu, Integer.MAX_VALUE);
            assertThrows(RuntimeException.class, () -> decode(protocol, unknownMenu));
        } finally {
            unknownMenu.release();
        }

        ByteBuf truncatedMenuId = Unpooled.buffer();
        try {
            VarInt.write(truncatedMenuId, PACKET_ID);
            VarInt.write(truncatedMenuId, 4);
            truncatedMenuId.writeByte(0x80);
            assertThrows(RuntimeException.class, () -> decode(protocol, truncatedMenuId));
        } finally {
            truncatedMenuId.release();
        }

        ByteBuf missingTitle = Unpooled.buffer();
        try {
            VarInt.write(missingTitle, PACKET_ID);
            VarInt.write(missingTitle, 4);
            VarInt.write(missingTitle, BuiltInRegistries.MENU.getId(MenuType.GENERIC_9x1));
            assertThrows(RuntimeException.class, () -> decode(protocol, missingTitle));
        } finally {
            missingTitle.release();
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPacketBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(
                protocol,
                packet(23, MenuType.MERCHANT, richTitles().get(2)));
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
        ByteBuf encoded = encodePacket(
                protocol, packet(31, MenuType.CARTOGRAPHY_TABLE, richTitles().getFirst()));
        try {
            encoded.writeByte(0x7A);
            ClientboundOpenScreenPacket decoded = assertInstanceOf(
                    ClientboundOpenScreenPacket.class, decode(protocol, encoded));
            assertEquals(31, decoded.getContainerId());
            assertSame(MenuType.CARTOGRAPHY_TABLE, decoded.getType());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static List<Component> richTitles() {
        return List.of(
                Component.empty(),
                Component.literal("Recorded container")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true)),
                Component.translatable("container.replay.menu", Component.literal("Market"))
                        .append(Component.literal(" open")
                                .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
                                        .withItalic(true))));
    }

    private static ClientboundOpenScreenPacket packet(
            int containerId, MenuType<?> menuType, Component title) {
        return new ClientboundOpenScreenPacket(containerId, menuType, title);
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundOpenScreenPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] originalWire = bytes(encoded);
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(expected.getContainerId(), VarInt.read(wire));
            assertEquals(
                    BuiltInRegistries.MENU.getId(expected.getType()), VarInt.read(wire));
            assertTrue(wire.isReadable(), "title component should follow container and menu IDs");

            ClientboundOpenScreenPacket decoded = assertInstanceOf(
                    ClientboundOpenScreenPacket.class, decode(protocol, encoded));
            assertEquals(expected.getContainerId(), decoded.getContainerId());
            assertSame(expected.getType(), decoded.getType());
            assertEquals(expected.getTitle().getString(), decoded.getTitle().getString());
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

    private static byte[] nativePayload(ClientboundOpenScreenPacket packet) {
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
                RegistryFriendlyByteBuf.decorator(registryAccess()));
    }

    private static RegistryAccess registryAccess() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
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
                throw new AssertionError("viewer-local container open must not encode");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local container open must not decode");
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
