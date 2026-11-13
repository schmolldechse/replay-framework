package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

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
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id022CooldownPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:cooldown";
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
    void classifiesCooldownAsViewerLocalUiState() {
        var definition = new Id022CooldownPacketDefinition();

        assertEquals(22, definition.packetId());
        assertEquals(0x16, Id022CooldownPacketDefinition.PACKET_ID);
        assertEquals("minecraft:cooldown", Id022CooldownPacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInTheUiDomainAndCatalog() {
        var domainMatches = UiPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id022CooldownPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id022CooldownPacketDefinition.class, catalogMatches.getFirst());
    }

    @Test
    void dropsEveryRecordedPayloadBeforeDecodeOrEncode() {
        var definition = new Id022CooldownPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[] {1, 'a'},
            new byte[] {(byte) 0x80},
            new byte[] {1, 'a', 0},
            new byte[] {1, 'a', (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                    (byte) 0xFF, 0x0F},
            new byte[] {(byte) 0xFF, 0, 1, 2, 3, 4, 5}
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
    void directRewriteDropsWithoutInspectingDecodedType() {
        PacketRewriteResult result = new Id022CooldownPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingLocalPolicy() {
        var definition = new Id022CooldownPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", 22, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 23, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 22, PacketPhase.CONFIGURATION,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 22, PacketPhase.PLAY,
                        PacketDescriptor.Direction.SERVERBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 22, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 22, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            frame(new byte[] {0}), mismatch, codec, rewriter()));
        }
    }

    @Test
    void enforcesNullBoundariesBeforeApplyingLocalPolicy() {
        var definition = new Id022CooldownPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), null, codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), descriptor(), null, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), descriptor(), codec, null));
        assertThrows(NullPointerException.class, () ->
                definition.rewrite(null, descriptor(), rewriter()));
    }

    @Test
    void nativeProtocolRoundTripsIdentifierShapesAndLengthBoundaries() {
        ProtocolInfo<?> protocol = protocol();

        for (String group : new String[] {
            "minecraft:ender_pearl",
            "custom:shared/path.to_group-part",
            identifierOfLength(127),
            identifierOfLength(128),
            identifierOfLength(16_383),
            identifierOfLength(16_384),
            identifierOfLength(21_845)
        }) {
            assertNativeRoundTrip(protocol, Identifier.parse(group), 20);
        }
    }

    @Test
    void nativeProtocolPreservesEverySignedDurationBoundary() {
        ProtocolInfo<?> protocol = protocol();
        Identifier group = Identifier.parse("replay:duration_boundary");

        for (int duration : new int[] {
            Integer.MIN_VALUE,
            -1,
            0,
            1,
            127,
            128,
            16_383,
            16_384,
            2_097_151,
            2_097_152,
            268_435_455,
            268_435_456,
            Integer.MAX_VALUE
        }) {
            assertNativeRoundTrip(protocol, group, duration);
        }
    }

    @Test
    void nativeProtocolUsesCanonicalVarIntWidths() {
        ProtocolInfo<?> protocol = protocol();
        Identifier group = Identifier.parse("replay:width");

        for (int duration : new int[] {
            0, 1, 127, 128, 16_383, 16_384, 2_097_151, 2_097_152,
            268_435_455, 268_435_456, Integer.MAX_VALUE, -1, Integer.MIN_VALUE
        }) {
            ByteBuf encoded = Unpooled.buffer();
            try {
                encode(protocol, encoded, new ClientboundCooldownPacket(group, duration));
                assertEquals(22, readVarInt(encoded));
                assertEquals(group.toString(), readUtf(encoded));
                assertEquals(varIntSize(duration), encoded.readableBytes());
                assertEquals(duration, readVarInt(encoded));
                assertFalse(encoded.isReadable());
            } finally {
                encoded.release();
            }
        }
    }

    @Test
    void nativeProtocolCanonicalizesAnImplicitMinecraftNamespace() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf input = Unpooled.buffer();
        ByteBuf reencoded = Unpooled.buffer();
        try {
            writeVarInt(input, 22);
            writeUtf(input, "ender_pearl");
            writeVarInt(input, 40);

            ClientboundCooldownPacket decoded = assertInstanceOf(
                    ClientboundCooldownPacket.class, decode(protocol, input));
            assertEquals(Identifier.parse("minecraft:ender_pearl"), decoded.cooldownGroup());
            assertEquals(40, decoded.duration());
            assertFalse(input.isReadable());

            encode(protocol, reencoded, decoded);
            assertEquals(22, readVarInt(reencoded));
            assertEquals("minecraft:ender_pearl", readUtf(reencoded));
            assertEquals(40, readVarInt(reencoded));
            assertFalse(reencoded.isReadable());
        } finally {
            input.release();
            reencoded.release();
        }
    }

    @Test
    void nativeProtocolKeepsReplacementRemovalAndDistinctGroupsIndependent() {
        ProtocolInfo<?> protocol = protocol();
        Identifier shared = Identifier.parse("replay:shared");
        Identifier distinct = Identifier.parse("replay:distinct");
        ByteBuf input = Unpooled.buffer();
        try {
            encode(protocol, input, new ClientboundCooldownPacket(shared, 20));
            encode(protocol, input, new ClientboundCooldownPacket(shared, 40));
            encode(protocol, input, new ClientboundCooldownPacket(shared, 0));
            encode(protocol, input, new ClientboundCooldownPacket(distinct, 5));

            assertCooldown(decode(protocol, input), shared, 20);
            assertCooldown(decode(protocol, input), shared, 40);
            assertCooldown(decode(protocol, input), shared, 0);
            assertCooldown(decode(protocol, input), distinct, 5);
            assertFalse(input.isReadable());
        } finally {
            input.release();
        }
    }

    @Test
    void nativeProtocolRejectsMalformedTruncatedInvalidAndOversizedIdentifiers() {
        ProtocolInfo<?> protocol = protocol();

        for (byte[] identifier : new byte[][] {
            new byte[0],
            new byte[] {(byte) 0x80},
            declaredUtf(3, new byte[] {'a'}),
            declaredUtf(1, new byte[] {(byte) 0x80}),
            encodedUtf(""),
            encodedUtf("Invalid:cooldown"),
            encodedUtf("minecraft:Invalid"),
            encodedUtf("minecraft:bad?path"),
            encodedUtf(identifierOfLength(21_846)),
            declaredUtf(32_768, new byte[0])
        }) {
            ByteBuf input = Unpooled.buffer();
            try {
                writeVarInt(input, 22);
                input.writeBytes(identifier);
                assertThrows(RuntimeException.class, () -> decode(protocol, input));
            } finally {
                input.release();
            }
        }
    }

    @Test
    void nativeProtocolRejectsMissingTruncatedAndOverlongDurations() {
        ProtocolInfo<?> protocol = protocol();

        for (byte[] duration : new byte[][] {
            new byte[0],
            new byte[] {(byte) 0x80},
            new byte[] {(byte) 0x80, (byte) 0x80},
            new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                    (byte) 0x80},
            new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                    (byte) 0x80, 0}
        }) {
            ByteBuf input = Unpooled.buffer();
            try {
                writeVarInt(input, 22);
                writeUtf(input, "replay:group");
                input.writeBytes(duration);
                assertThrows(RuntimeException.class, () -> decode(protocol, input));
            } finally {
                input.release();
            }
        }
    }

    @Test
    void nativeProtocolLeavesFollowingFrameBytesUntouched() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf input = Unpooled.buffer();
        try {
            encode(protocol, input, new ClientboundCooldownPacket(
                    Identifier.parse("replay:group"), 20));
            input.writeByte(0x5A);

            ClientboundCooldownPacket decoded = assertInstanceOf(
                    ClientboundCooldownPacket.class, decode(protocol, input));
            assertEquals(Identifier.parse("replay:group"), decoded.cooldownGroup());
            assertEquals(20, decoded.duration());
            assertEquals(0x5A, input.readUnsignedByte());
            assertFalse(input.isReadable());
        } finally {
            input.release();
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            Identifier expectedGroup,
            int expectedDuration) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, new ClientboundCooldownPacket(
                    expectedGroup, expectedDuration));

            ByteBuf wireView = encoded.duplicate();
            assertEquals(22, readVarInt(wireView));
            assertEquals(expectedGroup.toString(), readUtf(wireView));
            assertEquals(expectedDuration, readVarInt(wireView));
            assertFalse(wireView.isReadable());

            assertCooldown(decode(protocol, encoded), expectedGroup, expectedDuration);
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertCooldown(
            Packet<?> packet,
            Identifier expectedGroup,
            int expectedDuration) {
        ClientboundCooldownPacket cooldown = assertInstanceOf(
                ClientboundCooldownPacket.class, packet);
        assertEquals(expectedGroup, cooldown.cooldownGroup());
        assertEquals(expectedDuration, cooldown.duration());
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(
                TYPE_NAME,
                22,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                true,
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            String typeName,
            int packetId,
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        boolean clientbound = direction == PacketDescriptor.Direction.CLIENTBOUND;
        return new PacketDescriptor(
                typeName,
                phase,
                direction,
                packetId,
                PacketDisposition.STATEFUL,
                clientbound,
                replayable,
                false,
                "paper-26.2/" + typeName,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 22, payload);
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local cooldown must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local cooldown must not decode");
            }
        };
    }

    private static String identifierOfLength(int length) {
        if (length < 2) {
            throw new IllegalArgumentException("identifier length must include namespace separator");
        }
        return "a:" + "b".repeat(length - 2);
    }

    private static byte[] encodedUtf(String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        return declaredUtf(encoded.length, encoded);
    }

    private static byte[] declaredUtf(int declaredLength, byte[] content) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            writeVarInt(buffer, declaredLength);
            buffer.writeBytes(content);
            byte[] result = new byte[buffer.readableBytes()];
            buffer.readBytes(result);
            return result;
        } finally {
            buffer.release();
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

    private static String readUtf(ByteBuf buffer) {
        int length = readVarInt(buffer);
        String value = buffer.toString(buffer.readerIndex(), length, StandardCharsets.UTF_8);
        buffer.skipBytes(length);
        return value;
    }

    private static void writeUtf(ByteBuf buffer, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buffer, encoded.length);
        buffer.writeBytes(encoded);
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

    private static int varIntSize(int value) {
        int size = 1;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            size++;
            remaining >>>= 7;
        }
        return size;
    }
}
