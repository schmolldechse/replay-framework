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
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.NumberFormatTypes;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id110SetScorePacketDefinitionTest {

    private static final int PACKET_ID = 110;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_score";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000202");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesScoreUpdatesAsCapturedViewerLocalState() {
        var definition = new Id110SetScorePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x6E, Id110SetScorePacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_score", Id110SetScorePacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());

        var domainMatches = ScoreboardPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id110SetScorePacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id110SetScorePacketDefinition.class, catalogMatches.getFirst());

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
    void nativeCodecRoundTripsEveryOptionalDisplayAndNumberFormatCombination() {
        ProtocolInfo<?> protocol = protocol();
        List<Optional<Component>> displays = List.of(
                Optional.empty(),
                Optional.of(Component.empty()),
                Optional.of(Component.translatable(
                        "score.display", Component.literal("分數").withStyle(ChatFormatting.GOLD))));
        List<Optional<NumberFormat>> numberFormats = List.of(
                Optional.empty(),
                Optional.of(BlankFormat.INSTANCE),
                Optional.of(new StyledFormat(
                        Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true).withItalic(true))),
                Optional.of(new FixedFormat(Component.literal("固定分數 ✦")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE)))));
        int[] scores = {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};
        String[] owners = {
            "Alice",
            "",
            "00000000-0000-0000-0000-000000000abc",
            "fake/玩家-élève",
            "objective-owner-with-long-name"
        };
        String[] objectives = {"kills", "", "分數", "custom/objective", "long_objective_name"};

        int caseIndex = 0;
        for (Optional<Component> display : displays) {
            for (Optional<NumberFormat> numberFormat : numberFormats) {
                for (int score : scores) {
                    ClientboundSetScorePacket packet = packet(
                            owners[caseIndex % owners.length],
                            objectives[caseIndex % objectives.length],
                            score,
                            display,
                            numberFormat);
                    assertNativeRoundTrip(protocol, packet);
                    caseIndex++;
                }
            }
        }
    }

    @Test
    void nativeCodecUsesVersionMatchedIdAndExactFieldOrder() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundSetScorePacket expected = packet(
                "fake/玩家",
                "objective/élève",
                -2147483648,
                Optional.of(Component.translatable(
                        "score.custom_display", Component.literal("✦").withStyle(ChatFormatting.GOLD))),
                Optional.of(new FixedFormat(Component.literal("固定值"))));
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            RegistryFriendlyByteBuf fields = new RegistryFriendlyByteBuf(
                    encoded.duplicate(), registryAccess());
            assertEquals(0x6E, fields.readVarInt());
            assertEquals("fake/玩家", fields.readUtf());
            assertEquals("objective/élève", fields.readUtf());
            assertEquals(Integer.MIN_VALUE, fields.readVarInt());
            assertEquals(expected.display(), ComponentSerialization.TRUSTED_OPTIONAL_STREAM_CODEC
                    .decode(fields));
            assertEquals(expected.numberFormat(), NumberFormatTypes.OPTIONAL_STREAM_CODEC.decode(fields));
            assertFalse(fields.isReadable());
            assertEquals(GamePacketTypes.CLIENTBOUND_SET_SCORE, expected.type());

            ClientboundSetScorePacket decoded = assertInstanceOf(
                    ClientboundSetScorePacket.class, decode(protocol, encoded));
            assertEquals(expected, decoded);
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void scoreOwnerAndObjectiveRemainArbitraryScoreboardStrings() {
        ProtocolInfo<?> protocol = protocol();
        List<ClientboundSetScorePacket> packets = List.of(
                packet("Alice", "kills", 12, Optional.empty(), Optional.empty()),
                packet("00000000-0000-0000-0000-000000000001", "health", 20,
                        Optional.empty(), Optional.empty()),
                packet("fake-score-holder", "custom:objective", -7,
                        Optional.empty(), Optional.empty()),
                packet("玩家/élève", "分數", 0,
                        Optional.empty(), Optional.empty()),
                packet("", "", 0,
                        Optional.empty(), Optional.empty()));

        for (ClientboundSetScorePacket packet : packets) {
            assertNativeRoundTrip(protocol, packet);
        }
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedPayloadPrefixAndMalformedVarInts() {
        ProtocolInfo<?> protocol = protocol();
        List<ClientboundSetScorePacket> packets = List.of(
                packet("owner", "objective", 5, Optional.empty(), Optional.empty()),
                packet("owner", "objective", -1,
                        Optional.of(Component.literal("display")), Optional.empty()),
                packet("owner", "objective", 2,
                        Optional.empty(), Optional.of(BlankFormat.INSTANCE)),
                packet("owner", "objective", Integer.MAX_VALUE,
                        Optional.of(Component.translatable("score.display")),
                        Optional.of(new StyledFormat(Style.EMPTY.withItalic(true)))),
                packet("owner", "objective", Integer.MIN_VALUE,
                        Optional.of(Component.literal("custom")),
                        Optional.of(new FixedFormat(Component.literal("fixed")))));

        for (ClientboundSetScorePacket packet : packets) {
            byte[] completeBody = body(protocol, packet);
            for (int length = 0; length < completeBody.length; length++) {
                assertNativeDecodeFails(
                        protocol,
                        java.util.Arrays.copyOf(completeBody, length),
                        "truncated score payload at " + length + " of "
                                + completeBody.length + " bytes");
            }
        }

        assertNativeDecodeFails(protocol, new byte[] {(byte) 0x80});
        assertNativeDecodeFails(protocol, new byte[] {0, (byte) 0x80});
        assertNativeDecodeFails(protocol, new byte[] {0, 0, (byte) 0x80});
        assertNativeDecodeFails(protocol, new byte[] {0, 0, 0, 2});
        assertNativeDecodeFails(protocol, new byte[] {0, 0, 0, 0, 2});
    }

    @Test
    void nativeDecoderRejectsUnknownRegistryBackedNumberFormatIds() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf body = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf fields = new RegistryFriendlyByteBuf(body, registryAccess());
            fields.writeUtf("owner");
            fields.writeUtf("objective");
            fields.writeVarInt(1);
            fields.writeBoolean(false);
            fields.writeBoolean(true);
            fields.writeVarInt(127);

            assertNativeDecodeFails(protocol, bytes(body), "unknown number-format registry ID");
        } finally {
            body.release();
        }
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = encodePacket(
                protocol,
                packet("holder", "kills", 9,
                        Optional.of(Component.literal("nine")),
                        Optional.of(BlankFormat.INSTANCE)));
        try {
            wire.writeByte(0x7A);
            ClientboundSetScorePacket decoded = assertInstanceOf(
                    ClientboundSetScorePacket.class, decode(protocol, wire));

            assertEquals("holder", decoded.owner());
            assertEquals("kills", decoded.objectiveName());
            assertEquals(9, decoded.score());
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void playbackDropsValidAndMalformedScoreUpdatesBeforeCodecAccess() {
        ProtocolInfo<?> protocol = protocol();
        List<byte[]> payloads = new ArrayList<>();
        payloads.add(body(protocol, packet("Alice", "kills", 10,
                Optional.empty(), Optional.empty())));
        payloads.add(body(protocol, packet("fake/owner", "objective", Integer.MIN_VALUE,
                Optional.of(Component.literal("visible")),
                Optional.of(new FixedFormat(Component.literal("fixed"))))));
        payloads.add(body(protocol, packet("holder", "objective", 2,
                Optional.empty(), Optional.of(BlankFormat.INSTANCE))));
        payloads.add(new byte[0]);
        payloads.add(new byte[] {(byte) 0x80});
        payloads.add(new byte[] {1, 0, (byte) 0x80});
        payloads.add(new byte[] {1, 1, 1, 2});

        var definition = new Id110SetScorePacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        for (byte[] payload : payloads) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());
            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingTheDecodedPacket() {
        PacketRewriteResult result = new Id110SetScorePacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id110SetScorePacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND,
                        true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        true, PacketDescriptor.PlaybackScope.SAFE),
                descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                        PacketDescriptor.Direction.SERVERBOUND,
                        false, PacketDescriptor.PlaybackScope.UNSAFE),
                descriptor(TYPE_NAME, PACKET_ID, PacketPhase.CONFIGURATION,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                    frame(new byte[0]), mismatch, codec, rewriter()));
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundSetScorePacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] expectedWire = bytes(encoded);
            RegistryFriendlyByteBuf fields = new RegistryFriendlyByteBuf(
                    encoded.duplicate(), registryAccess());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(expected.owner(), fields.readUtf());
            assertEquals(expected.objectiveName(), fields.readUtf());
            assertEquals(expected.score(), fields.readVarInt());
            assertEquals(expected.display(), ComponentSerialization.TRUSTED_OPTIONAL_STREAM_CODEC
                    .decode(fields));
            assertEquals(expected.numberFormat(), NumberFormatTypes.OPTIONAL_STREAM_CODEC.decode(fields));
            assertFalse(fields.isReadable());

            ClientboundSetScorePacket decoded = assertInstanceOf(
                    ClientboundSetScorePacket.class, decode(protocol, encoded));
            assertEquals(expected, decoded);
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

    private static ClientboundSetScorePacket packet(
            String owner,
            String objective,
            int score,
            Optional<Component> display,
            Optional<NumberFormat> numberFormat) {
        return new ClientboundSetScorePacket(owner, objective, score, display, numberFormat);
    }

    private static byte[] body(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            ByteBuf body = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(body));
            return bytes(body);
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

    private static void assertNativeDecodeFails(
            ProtocolInfo<?> protocol, byte[] body, String description) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(body);
            assertThrows(RuntimeException.class, () -> decode(protocol, wire), description);
        } finally {
            wire.release();
        }
    }

    private static void assertNativeDecodeFails(ProtocolInfo<?> protocol, byte[] body) {
        assertNativeDecodeFails(protocol, body, "malformed packet body");
    }

    private static RegistryAccess registryAccess() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess()));
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
        boolean clientbound = direction == PacketDescriptor.Direction.CLIENTBOUND;
        return new PacketDescriptor(
                typeName,
                phase,
                direction,
                packetId,
                clientbound ? PacketDisposition.STATEFUL : PacketDisposition.CONTROL,
                clientbound,
                clientbound && replayable,
                false,
                "paper-26.2/" + typeName,
                clientbound ? scope : PacketDescriptor.PlaybackScope.UNSAFE);
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
                throw new AssertionError("viewer-local score update must not be encoded");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local score update must not be decoded");
            }
        };
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
