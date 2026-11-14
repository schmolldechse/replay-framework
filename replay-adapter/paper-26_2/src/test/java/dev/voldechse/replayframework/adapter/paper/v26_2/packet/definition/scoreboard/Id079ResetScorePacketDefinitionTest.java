package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.scoreboard;

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
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id079ResetScorePacketDefinitionTest {

    private static final int PACKET_ID = 79;
    private static final String TYPE_NAME = "play.clientbound.minecraft:reset_score";
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
    void classifiesScoreResetAsCapturedViewerLocalState() {
        var definition = new Id079ResetScorePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x4F, Id079ResetScorePacketDefinition.PACKET_ID);
        assertEquals("minecraft:reset_score", Id079ResetScorePacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInScoreboardDomainCatalogAndPaperRegistry() {
        var domainMatches = ScoreboardPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id079ResetScorePacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id079ResetScorePacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
    }

    @Test
    void dropsAllScoreResetFormsAndMalformedPayloadsBeforeCodecAccess() {
        var definition = new Id079ResetScorePacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        ProtocolInfo<?> protocol = protocol();

        List<byte[]> payloads = List.of(
                body(protocol, packet("Alice", "kills")),
                body(protocol, packet("entity-uuid-or-score-holder", null)),
                body(protocol, packet("", "")),
                new byte[0],
                new byte[] {(byte) 0x80},
                new byte[] {1, 0, 1},
                new byte[] {1, (byte) 0x80});

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
    void directRewriteDropsWithoutInspectingTheDecodedType() {
        PacketRewriteResult result = new Id079ResetScorePacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void validatesDescriptorAndNullBoundariesBeforeApplyingLocalPolicy() {
        var definition = new Id079ResetScorePacketDefinition();
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

        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketDescriptor.PlaybackScope.SAFE),
                descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                        PacketPhase.CONFIGURATION, PacketDescriptor.Direction.CLIENTBOUND, true),
                new PacketDescriptor(
                        TYPE_NAME,
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.SERVERBOUND,
                        PACKET_ID,
                        PacketDisposition.UNSUPPORTED,
                        false,
                        false,
                        false,
                        "paper-26.2/" + TYPE_NAME,
                        PacketDescriptor.PlaybackScope.UNSAFE),
                descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, false));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                    frame(new byte[0]), mismatch, codec, rewriter()));
        }
    }

    @Test
    void nativeProtocolUsesIdSeventyNineAndPreservesOwnerAndOptionalObjective() {
        ProtocolInfo<?> protocol = protocol();
        List<String> owners = List.of(
                "Alice",
                "00000000-0000-0000-0000-000000000001",
                "fake score holder:total",
                "",
                "玩家/élève");
        List<String> objectives = java.util.Arrays.asList(null, "kills", "", "移動距離");

        for (String owner : owners) {
            for (String objective : objectives) {
                assertNativeRoundTrip(protocol, packet(owner, objective));
            }
        }
    }

    @Test
    void nativeOptionalBooleanUsesNullForAllOwnerScoresAndPreservesPresentEmptyObjective() {
        ProtocolInfo<?> protocol = protocol();

        assertNativeRoundTrip(protocol, packet("owner", null));
        assertNativeRoundTrip(protocol, packet("owner", ""));

        ByteBuf allScores = packetBuffer("owner", 0, null);
        try {
            ClientboundResetScorePacket decoded = assertInstanceOf(
                    ClientboundResetScorePacket.class, decode(protocol, allScores));
            assertEquals("owner", decoded.owner());
            assertEquals(null, decoded.objectiveName());
            assertFalse(allScores.isReadable());
        } finally {
            allScores.release();
        }

        ByteBuf oneObjective = packetBuffer("owner", 1, "");
        try {
            ClientboundResetScorePacket decoded = assertInstanceOf(
                    ClientboundResetScorePacket.class, decode(protocol, oneObjective));
            assertEquals("owner", decoded.owner());
            assertEquals("", decoded.objectiveName());
            assertFalse(oneObjective.isReadable());
        } finally {
            oneObjective.release();
        }
    }

    @Test
    void nativeProtocolTreatsEveryNonzeroOptionalFlagAsPresent() {
        ProtocolInfo<?> protocol = protocol();

        for (int flag = 1; flag <= 0xFF; flag++) {
            ByteBuf raw = packetBuffer("owner", flag, "objective");
            try {
                ClientboundResetScorePacket decoded = assertInstanceOf(
                        ClientboundResetScorePacket.class, decode(protocol, raw));
                assertEquals("owner", decoded.owner());
                assertEquals("objective", decoded.objectiveName());
                assertFalse(raw.isReadable());

                ByteBuf canonical = encodePacket(protocol, decoded);
                try {
                    ByteBuf wire = canonical.duplicate();
                    assertEquals(PACKET_ID, readVarInt(wire));
                    assertEquals("owner", readUtf(wire));
                    assertEquals(1, wire.readUnsignedByte());
                    assertEquals("objective", readUtf(wire));
                    assertFalse(wire.isReadable());
                } finally {
                    canonical.release();
                }
            } finally {
                raw.release();
            }
        }
    }

    @Test
    void nativeProtocolAcceptsMaximumLengthStringsAndRejectsOverlongOnes() {
        ProtocolInfo<?> protocol = protocol();
        String maximum = "x".repeat(32_767);
        assertNativeRoundTrip(protocol, packet(maximum, maximum));

        for (byte[] body : new byte[][] {
            utf8Body("x".repeat(32_768), 0, null),
            utf8Body("owner", 1, "x".repeat(32_768))
        }) {
            ByteBuf raw = Unpooled.buffer();
            try {
                writeVarInt(raw, PACKET_ID);
                raw.writeBytes(body);
                assertThrows(RuntimeException.class, () -> decode(protocol, raw));
            } finally {
                raw.release();
            }
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPacketBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, new byte[0]);

        byte[] completeBody = body(protocol, packet("Alice", "kills"));
        for (int length = 0; length < completeBody.length; length++) {
            assertMalformed(protocol, java.util.Arrays.copyOf(completeBody, length));
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundResetScorePacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] expectedWire = bytes(encoded);
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            assertEquals(expected.owner(), readUtf(wire));
            if (expected.objectiveName() == null) {
                assertEquals(0, wire.readUnsignedByte());
            } else {
                assertEquals(1, wire.readUnsignedByte());
                assertEquals(expected.objectiveName(), readUtf(wire));
            }
            assertFalse(wire.isReadable());

            ClientboundResetScorePacket decoded = assertInstanceOf(
                    ClientboundResetScorePacket.class, decode(protocol, encoded));
            assertEquals(expected.owner(), decoded.owner());
            assertEquals(expected.objectiveName(), decoded.objectiveName());
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(expectedWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static ClientboundResetScorePacket packet(String owner, String objective) {
        return new ClientboundResetScorePacket(owner, objective);
    }

    private static byte[] body(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            return bytes(wire);
        } finally {
            encoded.release();
        }
    }

    private static ByteBuf bodyBuffer(String owner, int objectiveFlag, String objective) {
        ByteBuf buffer = Unpooled.buffer();
        writeUtf(buffer, owner);
        buffer.writeByte(objectiveFlag);
        if (objectiveFlag != 0) {
            writeUtf(buffer, objective);
        }
        return buffer;
    }

    private static ByteBuf packetBuffer(String owner, int objectiveFlag, String objective) {
        ByteBuf buffer = Unpooled.buffer();
        writeVarInt(buffer, PACKET_ID);
        writeUtf(buffer, owner);
        buffer.writeByte(objectiveFlag);
        if (objectiveFlag != 0) {
            writeUtf(buffer, objective);
        }
        return buffer;
    }

    private static byte[] utf8Body(String owner, int objectiveFlag, String objective) {
        ByteBuf buffer = bodyBuffer(owner, objectiveFlag, objective);
        try {
            return bytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void writeUtf(ByteBuf buffer, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buffer, encoded.length);
        buffer.writeBytes(encoded);
    }

    private static String readUtf(ByteBuf buffer) {
        int byteLength = readVarInt(buffer);
        byte[] encoded = new byte[byteLength];
        buffer.readBytes(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
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
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true);
    }

    private static PacketDescriptor descriptor(
            String typeName, int packetId, PacketDescriptor.PlaybackScope scope) {
        return descriptor(typeName, packetId, scope, PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND, true);
    }

    private static PacketDescriptor descriptor(
            String typeName,
            int packetId,
            PacketDescriptor.PlaybackScope scope,
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            boolean replayable) {
        return new PacketDescriptor(
                typeName,
                phase,
                direction,
                packetId,
                PacketDisposition.STATEFUL,
                true,
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
                throw new AssertionError("viewer-local score reset must not be encoded");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local score reset must not be decoded");
            }
        };
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] body) {
        ByteBuf input = Unpooled.buffer();
        try {
            writeVarInt(input, PACKET_ID);
            input.writeBytes(body);
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
        }
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
        throw new IllegalArgumentException("VarInt exceeds five bytes");
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            buffer.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buffer.writeByte(value);
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
