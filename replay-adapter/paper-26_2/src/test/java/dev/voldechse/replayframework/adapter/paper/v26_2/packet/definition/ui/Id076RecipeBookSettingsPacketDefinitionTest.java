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
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRecipeBookSettingsPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.stats.RecipeBookSettings;
import net.minecraft.world.inventory.RecipeBookType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id076RecipeBookSettingsPacketDefinitionTest {

    private static final int PACKET_ID = 0x4C;
    private static final String TYPE_NAME = "play.clientbound.minecraft:recipe_book_settings";
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
    void classifiesAndRegistersPerConnectionRecipeBookPreferences() {
        var definition = new Id076RecipeBookSettingsPacketDefinition();

        assertEquals(76, definition.packetId());
        assertEquals(PACKET_ID, Id076RecipeBookSettingsPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:recipe_book_settings",
                Id076RecipeBookSettingsPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());

        var domainMatches = UiPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id076RecipeBookSettingsPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id076RecipeBookSettingsPacketDefinition.class, catalogMatches.getFirst());

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
    void nativeCodecRoundTripsAll256CombinationsInDocumentedWireOrder() {
        ProtocolInfo<?> protocol = protocol();

        assertEquals(4, RecipeBookType.values().length);
        for (int flags = 0; flags < 256; flags++) {
            RecipeBookSettings settings = settings(flags);
            ClientboundRecipeBookSettingsPacket expected =
                    new ClientboundRecipeBookSettingsPacket(settings);
            ByteBuf encoded = encodePacket(protocol, expected);
            try {
                byte[] originalWire = bytes(encoded);
                assertEquals(PACKET_ID, VarInt.read(encoded));
                for (int field = 0; field < 8; field++) {
                    assertEquals((flags & (1 << field)) != 0, encoded.readBoolean());
                }
                assertFalse(encoded.isReadable());

                ClientboundRecipeBookSettingsPacket decoded = assertInstanceOf(
                        ClientboundRecipeBookSettingsPacket.class,
                        decode(protocol, originalWire));
                assertSettingsEquals(settings, decoded.bookSettings());

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
    void nativeDecoderTreatsEveryNonZeroBooleanAsTrueAndNormalizesOnEncode() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeByte(0x02);
            wire.writeByte(0x00);
            wire.writeByte(0x7F);
            wire.writeByte(0x00);
            wire.writeByte(0xFF);
            wire.writeByte(0x00);
            wire.writeByte(0x01);
            wire.writeByte(0x00);

            ClientboundRecipeBookSettingsPacket decoded = assertInstanceOf(
                    ClientboundRecipeBookSettingsPacket.class, decode(protocol, wire));
            RecipeBookSettings settings = decoded.bookSettings();
            assertTrue(settings.isOpen(RecipeBookType.CRAFTING));
            assertFalse(settings.isFiltering(RecipeBookType.CRAFTING));
            assertTrue(settings.isOpen(RecipeBookType.FURNACE));
            assertFalse(settings.isFiltering(RecipeBookType.FURNACE));
            assertTrue(settings.isOpen(RecipeBookType.BLAST_FURNACE));
            assertFalse(settings.isFiltering(RecipeBookType.BLAST_FURNACE));
            assertTrue(settings.isOpen(RecipeBookType.SMOKER));
            assertFalse(settings.isFiltering(RecipeBookType.SMOKER));

            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertEquals(PACKET_ID, VarInt.read(reencoded));
                for (int field = 0; field < 8; field++) {
                    assertEquals(field % 2 == 0, reencoded.readBoolean());
                }
                assertFalse(reencoded.isReadable());
            } finally {
                reencoded.release();
            }
        } finally {
            wire.release();
        }
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundRecipeBookSettingsPacket expected =
                new ClientboundRecipeBookSettingsPacket(settings(0xA5));
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            encoded.writeByte(0x7A);
            ClientboundRecipeBookSettingsPacket decoded = assertInstanceOf(
                    ClientboundRecipeBookSettingsPacket.class, decode(protocol, encoded));
            assertSettingsEquals(expected.bookSettings(), decoded.bookSettings());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedSettingsBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completeBody = {1, 0, 1, 0, 1, 0, 1, 0};

        for (int length = 0; length < completeBody.length; length++) {
            assertMalformed(protocol, java.util.Arrays.copyOf(completeBody, length));
        }
    }

    @Test
    void dropsAll256PreferenceStatesAndMalformedPayloadsBeforeCodecUse() {
        var definition = new Id076RecipeBookSettingsPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        ProtocolInfo<?> protocol = protocol();

        for (int flags = 0; flags < 256; flags++) {
            byte[] payload = nativePayload(
                    protocol,
                    new ClientboundRecipeBookSettingsPacket(settings(flags)));
            assertDropBeforeCodec(definition, codec, payload);
        }
        for (byte[] malformed : List.of(
                new byte[0],
                new byte[] {1},
                new byte[] {1, 0, 1},
                new byte[] {1, 0, 1, 0, 1, 0, 1})) {
            assertDropBeforeCodec(definition, codec, malformed);
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsRecordedRecipeBookUiPreferences() {
        PacketRewriteResult result = new Id076RecipeBookSettingsPacketDefinition()
                .rewrite(new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
        assertEquals(
                "recorded connection state does not belong to the observer",
                result.reason());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id076RecipeBookSettingsPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.CONFIGURATION, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.SERVERBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                    frame(new byte[0]), mismatch, codec, rewriter()));
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.rewrite(new Object(), mismatch, rewriter()));
        }
    }

    private static RecipeBookSettings settings(int flags) {
        RecipeBookSettings settings = new RecipeBookSettings();
        for (RecipeBookType type : RecipeBookType.values()) {
            int openBit = type.ordinal() * 2;
            settings.setOpen(type, (flags & (1 << openBit)) != 0);
            settings.setFiltering(type, (flags & (1 << (openBit + 1))) != 0);
        }
        return settings;
    }

    private static void assertSettingsEquals(
            RecipeBookSettings expected, RecipeBookSettings actual) {
        for (RecipeBookType type : RecipeBookType.values()) {
            assertEquals(expected.isOpen(type), actual.isOpen(type));
            assertEquals(expected.isFiltering(type), actual.isFiltering(type));
        }
    }

    private static void assertDropBeforeCodec(
            Id076RecipeBookSettingsPacketDefinition definition,
            PacketCodec<Object> codec,
            byte[] payload) {
        PacketRewriteResult result = definition.handlePlayback(
                frame(payload), descriptor(), codec, rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
        assertEquals(
                "recorded connection state does not belong to the observer",
                result.reason());
    }

    private static byte[] nativePayload(
            ProtocolInfo<?> protocol, ClientboundRecipeBookSettingsPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
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

    private static ClientboundRecipeBookSettingsPacket decode(
            ProtocolInfo<?> protocol, byte[] wireBytes) {
        ByteBuf wire = Unpooled.wrappedBuffer(wireBytes);
        try {
            return decode(protocol, wire);
        } finally {
            wire.release();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ClientboundRecipeBookSettingsPacket decode(
            ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (ClientboundRecipeBookSettingsPacket)
                ((StreamCodec) protocol.codec()).decode(buffer);
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] body) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(body);
            assertThrows(RuntimeException.class, () -> decode(protocol, wire));
        } finally {
            wire.release();
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
        return descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND, true,
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            String typeName,
            int packetId,
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                typeName,
                phase,
                direction,
                packetId,
                PacketDisposition.STATEFUL,
                direction == PacketDescriptor.Direction.CLIENTBOUND,
                replayable,
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
                throw new AssertionError("viewer-local recipe book settings must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local recipe book settings must not decode");
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }
}
