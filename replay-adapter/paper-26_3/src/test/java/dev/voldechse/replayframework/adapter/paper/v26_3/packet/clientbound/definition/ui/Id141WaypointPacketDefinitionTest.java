package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

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
import dev.voldechse.replayframework.adapter.paper.v26_3.Paper26_3PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3ClientboundPacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3ClientboundPacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
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
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.waypoints.TrackedWaypoint;
import net.minecraft.world.waypoints.Waypoint;
import net.minecraft.world.waypoints.WaypointStyleAsset;
import net.minecraft.world.waypoints.WaypointStyleAssets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id141WaypointPacketDefinitionTest {

    private static final int PACKET_ID = 141;
    private static final String TYPE_NAME = "play.clientbound.minecraft:waypoint";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void classifiesWaypointsAsViewerLocalLocatorBarState() {
        var definition = new Id141WaypointPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x8D, Id141WaypointPacketDefinition.PACKET_ID);
        assertEquals("minecraft:waypoint", Id141WaypointPacketDefinition.IDENTIFIER);
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
        var catalogMatches = Paper26_3ClientboundPacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id141WaypointPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id141WaypointPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_3PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
        assertTrue(Paper26_3ClientboundPacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void doesNotAssignViewerLocalWaypointsToCheckpointState() {
        assertTrue(Paper26_3ClientboundPacketCatalog.catalog().require(TYPE_NAME).checkpointFamily().isEmpty());
    }

    @Test
    void nativeProtocolRoundTripsEveryOperationAndWaypointDataVariant() {
        ProtocolInfo<?> protocol = protocol();
        UUID uuid = UUID.fromString("f1234567-89ab-cdef-0123-456789abcdef");
        Waypoint.Icon defaultIcon = icon(WaypointStyleAssets.DEFAULT, Optional.empty());
        ResourceKey<WaypointStyleAsset> customStyle = ResourceKey.create(
                WaypointStyleAssets.ROOT_ID,
                Identifier.fromNamespaceAndPath("replay_test", "waypoint_style/custom"));
        int coloredArgb = ARGB.color(0x12, 0xAB, 0xEF);
        int blackArgb = ARGB.color(0, 0, 0);
        Waypoint.Icon coloredIcon = icon(customStyle, Optional.of(coloredArgb));
        Waypoint.Icon blackIcon = icon(WaypointStyleAssets.BOWTIE, Optional.of(blackArgb));

        List<PacketCase> packets = List.of(
                new PacketCase(
                        ClientboundTrackedWaypointPacket.removeWaypoint(uuid),
                        1,
                        0,
                        uuid,
                        WaypointStyleAssets.DEFAULT,
                        Optional.empty(),
                        new int[0],
                        null),
                new PacketCase(
                        ClientboundTrackedWaypointPacket.addWaypointPosition(
                                uuid, defaultIcon, new Vec3i(Integer.MIN_VALUE, -1, Integer.MAX_VALUE)),
                        0,
                        1,
                        uuid,
                        WaypointStyleAssets.DEFAULT,
                        Optional.empty(),
                        new int[] {Integer.MIN_VALUE, -1, Integer.MAX_VALUE},
                        null),
                new PacketCase(
                        ClientboundTrackedWaypointPacket.addWaypointChunk(
                                uuid, coloredIcon, new ChunkPos(Integer.MIN_VALUE, Integer.MAX_VALUE)),
                        0,
                        2,
                        uuid,
                        customStyle,
                        Optional.of(coloredArgb),
                        new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE},
                        null),
                new PacketCase(
                        ClientboundTrackedWaypointPacket.addWaypointAzimuth(
                                uuid, blackIcon, Float.intBitsToFloat(0x7FC12345)),
                        0,
                        3,
                        uuid,
                        WaypointStyleAssets.BOWTIE,
                        Optional.of(blackArgb),
                        new int[0],
                        0x7FC12345),
                new PacketCase(
                        ClientboundTrackedWaypointPacket.updateWaypointPosition(
                                uuid, coloredIcon, new Vec3i(0, 2048, -2048)),
                        2,
                        1,
                        uuid,
                        customStyle,
                        Optional.of(coloredArgb),
                        new int[] {0, 2048, -2048},
                        null),
                new PacketCase(
                        ClientboundTrackedWaypointPacket.updateWaypointChunk(
                                uuid, defaultIcon, new ChunkPos(-17, 23)),
                        2,
                        2,
                        uuid,
                        WaypointStyleAssets.DEFAULT,
                        Optional.empty(),
                        new int[] {-17, 23},
                        null),
                new PacketCase(
                        ClientboundTrackedWaypointPacket.updateWaypointAzimuth(
                                uuid, coloredIcon, -0.0F),
                        2,
                        3,
                        uuid,
                        customStyle,
                        Optional.of(coloredArgb),
                        new int[0],
                        Float.floatToRawIntBits(-0.0F)));

        for (PacketCase packetCase : packets) {
            assertNativeRoundTrip(protocol, packetCase);
        }
    }

    @Test
    void nativeProtocolPreservesStringIdentifiersAndCustomIconStyles() {
        ProtocolInfo<?> protocol = protocol();
        String identifier = "server:quest/雪/😀";
        Waypoint.Icon icon = icon(
                ResourceKey.create(
                        WaypointStyleAssets.ROOT_ID,
                        Identifier.fromNamespaceAndPath("example", "special/waypoint")),
                Optional.of(ARGB.color(255, 255, 255)));
        FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VarInt.write(input, PACKET_ID);
            VarInt.write(input, 0); // TRACK
            input.writeBoolean(false); // string identifier branch
            input.writeUtf(identifier);
            Waypoint.Icon.STREAM_CODEC.encode(input, icon);
            VarInt.write(input, 1); // VEC3I
            VarInt.write(input, 12);
            VarInt.write(input, -64);
            VarInt.write(input, 900);
            byte[] originalWire = bytes(input);

            var decoded = assertInstanceOf(
                    ClientboundTrackedWaypointPacket.class, decode(protocol, input));
            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(originalWire, bytes(reencoded));
                FriendlyByteBuf wire = new FriendlyByteBuf(reencoded.duplicate());
                assertEquals(PACKET_ID, VarInt.read(wire));
                assertEquals(0, VarInt.read(wire));
                assertFalse(wire.readBoolean());
                assertEquals(identifier, wire.readUtf());
                Waypoint.Icon decodedIcon = Waypoint.Icon.STREAM_CODEC.decode(wire);
                assertEquals(icon.style, decodedIcon.style);
                assertEquals(icon.color, decodedIcon.color);
                assertEquals(1, VarInt.read(wire));
                assertEquals(12, VarInt.read(wire));
                assertEquals(-64, VarInt.read(wire));
                assertEquals(900, VarInt.read(wire));
                assertFalse(wire.isReadable());
            } finally {
                reencoded.release();
            }
        } finally {
            input.release();
        }
    }

    @Test
    void nativeProtocolAcceptsMaximumStringIdentifierAndRejectsLongerIdentifiers() {
        ProtocolInfo<?> protocol = protocol();
        String maximumIdentifier = "x".repeat(32767);
        ByteBuf valid = stringIdentifierPacket(maximumIdentifier);
        try {
            var decoded = assertInstanceOf(
                    ClientboundTrackedWaypointPacket.class, decode(protocol, valid));
            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                FriendlyByteBuf wire = new FriendlyByteBuf(reencoded.duplicate());
                assertEquals(PACKET_ID, VarInt.read(wire));
                assertEquals(0, VarInt.read(wire));
                assertFalse(wire.readBoolean());
                assertEquals(maximumIdentifier, wire.readUtf());
            } finally {
                reencoded.release();
            }
        } finally {
            valid.release();
        }

        FriendlyByteBuf tooLong = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VarInt.write(tooLong, PACKET_ID);
            VarInt.write(tooLong, 0);
            tooLong.writeBoolean(false);
            assertThrows(RuntimeException.class, () -> tooLong.writeUtf("x".repeat(32768)));
        } finally {
            tooLong.release();
        }
    }

    @Test
    void nativeProtocolWrapsUnknownOperationIdsUsingThePaperEnumCodecPolicy() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(
                protocol,
                ClientboundTrackedWaypointPacket.removeWaypoint(
                        UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")));
        try {
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(1, VarInt.read(wire));
            encoded.setByte(wire.readerIndex() - 1, 3); // out-of-range operation wraps to TRACK

            var decoded = assertInstanceOf(
                    ClientboundTrackedWaypointPacket.class, decode(protocol, encoded));
            ByteBuf canonical = encodePacket(protocol, decoded);
            try {
                assertEquals(PACKET_ID, VarInt.read(canonical));
                assertEquals(0, VarInt.read(canonical));
            } finally {
                canonical.release();
            }
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeProtocolRejectsUnknownWaypointKindsAndEveryTruncatedBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(
                protocol,
                ClientboundTrackedWaypointPacket.addWaypointPosition(
                        UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                        icon(WaypointStyleAssets.DEFAULT, Optional.empty()),
                        new Vec3i(12, 64, -31)));
        try {
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            byte[] body = bytes(wire);
            for (int length = 0; length < body.length; length++) {
                assertMalformed(protocol, java.util.Arrays.copyOf(body, length));
            }
        } finally {
            encoded.release();
        }

        FriendlyByteBuf invalidKind = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VarInt.write(invalidKind, PACKET_ID);
            VarInt.write(invalidKind, 0);
            invalidKind.writeBoolean(true);
            UUIDUtil.STREAM_CODEC.encode(
                    invalidKind,
                    UUID.fromString("12345678-1234-1234-1234-123456789abc"));
            Waypoint.Icon.STREAM_CODEC.encode(
                    invalidKind, icon(WaypointStyleAssets.DEFAULT, Optional.empty()));
            VarInt.write(invalidKind, 4);
            assertThrows(RuntimeException.class, () -> decode(protocol, invalidKind));
        } finally {
            invalidKind.release();
        }
    }

    @Test
    void dropsValidAndMalformedWaypointFramesBeforePlaybackCodecAccess() {
        var definition = new Id141WaypointPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<byte[]> payloads = List.of(
                nativePayload(ClientboundTrackedWaypointPacket.removeWaypoint(OBSERVER_ID)),
                nativePayload(ClientboundTrackedWaypointPacket.addWaypointChunk(
                        OBSERVER_ID,
                        icon(WaypointStyleAssets.BOWTIE, Optional.of(ARGB.color(0xCC, 0x88, 0x44))),
                        new ChunkPos(-10, 20))),
                nativePayload(ClientboundTrackedWaypointPacket.updateWaypointAzimuth(
                        OBSERVER_ID,
                        icon(WaypointStyleAssets.DEFAULT, Optional.empty()),
                        (float) Math.PI)),
                new byte[0],
                new byte[] {(byte) 0x80},
                new byte[] {0});

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
    void directRewriteDropsWithoutInspectingTheDecodedWaypointPacket() {
        PacketRewriteResult result = new Id141WaypointPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id141WaypointPacketDefinition();
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
    void nativeProtocolLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(
                protocol,
                ClientboundTrackedWaypointPacket.addWaypointPosition(
                        OBSERVER_ID,
                        icon(WaypointStyleAssets.DEFAULT, Optional.empty()),
                        new Vec3i(1, 2, 3)));
        try {
            encoded.writeByte(0x7A);
            assertInstanceOf(ClientboundTrackedWaypointPacket.class, decode(protocol, encoded));
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertNativeRoundTrip(ProtocolInfo<?> protocol, PacketCase packetCase) {
        ByteBuf encoded = encodePacket(protocol, packetCase.packet());
        try {
            byte[] originalWire = bytes(encoded);
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(packetCase.operation(), VarInt.read(wire));
            assertEquals(
                    Either.left(packetCase.identifier()),
                    wire.readEither(UUIDUtil.STREAM_CODEC, FriendlyByteBuf::readUtf));
            Waypoint.Icon icon = Waypoint.Icon.STREAM_CODEC.decode(wire);
            assertEquals(packetCase.style(), icon.style);
            assertEquals(packetCase.color(), icon.color);
            assertEquals(packetCase.kind(), VarInt.read(wire));
            switch (packetCase.kind()) {
                case 0 -> assertEquals(0, packetCase.coordinates().length);
                case 1, 2 -> {
                    for (int coordinate : packetCase.coordinates()) {
                        assertEquals(coordinate, VarInt.read(wire));
                    }
                }
                case 3 -> assertEquals(
                        packetCase.angleBits(), Float.floatToRawIntBits(wire.readFloat()));
                default -> throw new AssertionError("unexpected test waypoint kind");
            }
            assertFalse(wire.isReadable());

            var decoded = assertInstanceOf(ClientboundTrackedWaypointPacket.class, decode(protocol, encoded));
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

    private static Waypoint.Icon icon(
            ResourceKey<WaypointStyleAsset> style, Optional<Integer> color) {
        Waypoint.Icon icon = new Waypoint.Icon();
        icon.style = style;
        icon.color = color;
        return icon;
    }

    private static ByteBuf stringIdentifierPacket(String identifier) {
        FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VarInt.write(input, PACKET_ID);
            VarInt.write(input, 0);
            input.writeBoolean(false);
            input.writeUtf(identifier);
            Waypoint.Icon.STREAM_CODEC.encode(
                    input, icon(WaypointStyleAssets.DEFAULT, Optional.empty()));
            VarInt.write(input, 0);
            return input.retainedDuplicate();
        } finally {
            input.release();
        }
    }

    private static byte[] nativePayload(Packet<?> packet) {
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

    private static Paper26_3ReplayPacketRewriter rewriter() {
        return new Paper26_3ReplayPacketRewriter(
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
                "paper-26.3/" + typeName,
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

    private record PacketCase(
            Packet<?> packet,
            int operation,
            int kind,
            UUID identifier,
            ResourceKey<WaypointStyleAsset> style,
            Optional<Integer> color,
            int[] coordinates,
            Integer angleBits) {}
}
