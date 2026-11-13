package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id051MapItemDataPacketDefinitionTest {

    private static final int PACKET_ID = 51;
    private static final String TYPE_NAME = "play.clientbound.minecraft:map_item_data";
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
    void classifiesMapUpdatesAsCapturedViewerLocalState() {
        var definition = new Id051MapItemDataPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x33, Id051MapItemDataPacketDefinition.PACKET_ID);
        assertEquals("minecraft:map_item_data", Id051MapItemDataPacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInTheWorldDomainCatalogAndPaperRegistry() {
        var domainMatches = WorldPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id051MapItemDataPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id051MapItemDataPacketDefinition.class, catalogMatches.getFirst());

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
    void dropsCapturedRawFramesBeforeDecodeOrEncode() {
        var definition = new Id051MapItemDataPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        byte[] validWirePacket = encodePacketBytes(protocol(), packet(
                20,
                (byte) 2,
                true,
                Optional.of(List.of()),
                Optional.of(new MapItemSavedData.MapPatch(
                        3, 5, 2, 2, new byte[] {0, 1, (byte) 0x80, (byte) 0xFF}))));
        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[] {1, 2, 3},
            new byte[] {(byte) 0xFF, (byte) 0x80, 0},
            Arrays.copyOfRange(validWirePacket, 1, validWirePacket.length)
        }) {
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
    void directRewriteDropsWithoutInspectingTheDecodedPacketType() {
        PacketRewriteResult result = new Id051MapItemDataPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingLocalPolicy() {
        var definition = new Id051MapItemDataPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            frame(new byte[0]), mismatch, codec, rewriter()));
        }
    }

    @Test
    void nativeCodecPreservesMapIdScaleLockAndOptionalBranches() {
        ProtocolInfo<?> protocol = protocol();
        int[] mapIds = {
            Integer.MIN_VALUE, -1, 0, 1, 127, 128, 16_383, 16_384, Integer.MAX_VALUE
        };
        byte[] scales = {Byte.MIN_VALUE, -1, 0, 1, 4, Byte.MAX_VALUE};

        for (int mapId : mapIds) {
            for (byte scale : scales) {
                for (boolean locked : new boolean[] {false, true}) {
                    assertNativeRoundTrip(protocol, packet(
                            mapId, scale, locked, Optional.empty(), Optional.empty()));
                }
            }
        }

        assertNativeRoundTrip(protocol, packet(
                7, (byte) 0, false, Optional.of(List.of()), Optional.empty()));
    }

    @Test
    void nativeCodecPreservesEveryBuiltInDecorationTypeAndOptionalName() {
        ProtocolInfo<?> protocol = protocol();
        List<MapDecoration> decorations = allBuiltInDecorations();

        assertFalse(decorations.isEmpty());
        assertNativeRoundTrip(protocol, packet(
                1, (byte) 3, true, Optional.of(decorations), Optional.empty()));

        for (int rawByte = Byte.MIN_VALUE; rawByte <= Byte.MAX_VALUE; rawByte++) {
            MapDecoration decoration = decoration(
                    BuiltInRegistries.MAP_DECORATION_TYPE.getAny().orElseThrow(),
                    (byte) rawByte,
                    (byte) -rawByte,
                    (byte) (rawByte ^ 0x55),
                    rawByte % 2 == 0
                            ? Optional.of(Component.literal("name-" + rawByte))
                            : Optional.empty());
            assertNativeRoundTrip(protocol, packet(
                    2, (byte) rawByte, rawByte % 2 == 0,
                    Optional.of(List.of(decoration)), Optional.empty()));
        }
    }

    @Test
    void nativeCodecPreservesPartialAndFullColorPatches() {
        ProtocolInfo<?> protocol = protocol();
        byte[] palette = new byte[256];
        for (int index = 0; index < palette.length; index++) {
            palette[index] = (byte) index;
        }
        assertNativeRoundTrip(protocol, packet(
                3, (byte) 1, false, Optional.empty(), Optional.of(
                        new MapItemSavedData.MapPatch(127, 128, 16, 16, palette))));

        byte[] fullMapColors = new byte[128 * 128];
        for (int index = 0; index < fullMapColors.length; index++) {
            fullMapColors[index] = (byte) (index * 31);
        }
        assertNativeRoundTrip(protocol, packet(
                Integer.MAX_VALUE,
                Byte.MAX_VALUE,
                true,
                Optional.of(allBuiltInDecorations()),
                Optional.of(new MapItemSavedData.MapPatch(
                        0, 127, 128, 128, fullMapColors))));
    }

    @Test
    void nativeCodecRejectsEveryTruncatedPrefixAndLeavesTrailingFrameBytes() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundMapItemDataPacket expected = packet(
                14,
                (byte) 2,
                true,
                Optional.of(allBuiltInDecorations()),
                Optional.of(new MapItemSavedData.MapPatch(
                        1, 2, 2, 2, new byte[] {0, 1, 2, 3})));
        byte[] encoded = encodePacketBytes(protocol, expected);

        for (int length = 0; length < encoded.length; length++) {
            ByteBuf truncated = Unpooled.wrappedBuffer(Arrays.copyOf(encoded, length));
            try {
                assertThrows(RuntimeException.class, () -> decode(protocol, truncated),
                        "truncated length " + length);
            } finally {
                truncated.release();
            }
        }

        ByteBuf withTrailingByte = Unpooled.buffer();
        try {
            withTrailingByte.writeBytes(encoded);
            withTrailingByte.writeByte(0x7A);

            assertEquivalent(expected, assertInstanceOf(
                    ClientboundMapItemDataPacket.class, decode(protocol, withTrailingByte)));
            assertEquals(0x7A, withTrailingByte.readUnsignedByte());
            assertFalse(withTrailingByte.isReadable());
        } finally {
            withTrailingByte.release();
        }
    }

    private static List<MapDecoration> allBuiltInDecorations() {
        ArrayList<MapDecoration> decorations = new ArrayList<>();
        int index = 0;
        for (MapDecorationType type : BuiltInRegistries.MAP_DECORATION_TYPE) {
            Identifier key = BuiltInRegistries.MAP_DECORATION_TYPE.getKey(type);
            Holder.Reference<MapDecorationType> holder = BuiltInRegistries.MAP_DECORATION_TYPE
                    .get(key)
                    .orElseThrow();
            decorations.add(decoration(
                    holder,
                    (byte) (index * 17),
                    (byte) (index * 31),
                    (byte) (index * 7),
                    index % 2 == 0
                            ? Optional.of(Component.literal("decoration-" + key))
                            : Optional.empty()));
            index++;
        }
        return List.copyOf(decorations);
    }

    private static MapDecoration decoration(
            Holder<MapDecorationType> type,
            byte x,
            byte y,
            byte rotation,
            Optional<Component> name) {
        return new MapDecoration(type, x, y, rotation, name);
    }

    private static ClientboundMapItemDataPacket packet(
            int mapId,
            byte scale,
            boolean locked,
            Optional<List<MapDecoration>> decorations,
            Optional<MapItemSavedData.MapPatch> colorPatch) {
        return new ClientboundMapItemDataPacket(
                new MapId(mapId), scale, locked, decorations, colorPatch);
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            ClientboundMapItemDataPacket expected) {
        byte[] originalWire = encodePacketBytes(protocol, expected);
        ByteBuf encoded = Unpooled.wrappedBuffer(originalWire);
        try {
            ByteBuf header = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(header));

            ClientboundMapItemDataPacket decoded = assertInstanceOf(
                    ClientboundMapItemDataPacket.class, decode(protocol, encoded));
            assertEquivalent(expected, decoded);
            assertFalse(encoded.isReadable());
            assertArrayEquals(originalWire, encodePacketBytes(protocol, decoded));
        } finally {
            encoded.release();
        }
    }

    private static void assertEquivalent(
            ClientboundMapItemDataPacket expected,
            ClientboundMapItemDataPacket actual) {
        assertEquals(expected.mapId(), actual.mapId());
        assertEquals(expected.scale(), actual.scale());
        assertEquals(expected.locked(), actual.locked());
        assertEquals(expected.decorations(), actual.decorations());
        assertEquals(expected.colorPatch().isPresent(), actual.colorPatch().isPresent());
        if (expected.colorPatch().isPresent()) {
            MapItemSavedData.MapPatch expectedPatch = expected.colorPatch().orElseThrow();
            MapItemSavedData.MapPatch actualPatch = actual.colorPatch().orElseThrow();
            assertEquals(expectedPatch.startX(), actualPatch.startX());
            assertEquals(expectedPatch.startY(), actualPatch.startY());
            assertEquals(expectedPatch.width(), actualPatch.width());
            assertEquals(expectedPatch.height(), actualPatch.height());
            assertArrayEquals(expectedPatch.mapColors(), actualPatch.mapColors());
        }
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
    }

    private static byte[] encodePacketBytes(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, packet);
            byte[] bytes = new byte[encoded.readableBytes()];
            encoded.getBytes(encoded.readerIndex(), bytes);
            return bytes;
        } finally {
            encoded.release();
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
            String typeName,
            int packetId,
            PacketDescriptor.PlaybackScope scope) {
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
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local map data must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local map data must not decode");
            }
        };
    }
}
