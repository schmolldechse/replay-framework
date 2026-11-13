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
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id015CommandSuggestionsPacketDefinitionTest {

    private static final int PACKET_ID = 15;
    private static final String IDENTIFIER = "minecraft:command_suggestions";
    private static final String TYPE_NAME = "play.clientbound.minecraft:command_suggestions";
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
    void classifiesCommandSuggestionsAsViewerLocalEphemeralData() {
        var definition = new Id015CommandSuggestionsPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x0F, Id015CommandSuggestionsPacketDefinition.PACKET_ID);
        assertEquals(IDENTIFIER, Id015CommandSuggestionsPacketDefinition.IDENTIFIER);
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
        assertInstanceOf(Id015CommandSuggestionsPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id015CommandSuggestionsPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
    }

    @Test
    void dropsEveryRecordedPayloadBeforeDecodeOrEncode() {
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : List.of(
                new byte[0],
                new byte[] {0},
                new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                new byte[] {1, 2, 3, 4, 5, 6, 7, 8})) {
            PacketRewriteResult result = new Id015CommandSuggestionsPacketDefinition()
                    .handlePlayback(frame(payload), descriptor(), codec, rewriter());

            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
            assertEquals(
                    "recorded connection state does not belong to the observer", result.reason());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingDecodedType() {
        PacketRewriteResult result = new Id015CommandSuggestionsPacketDefinition()
                .rewrite(new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesAndNullBoundaries() {
        var definition = new Id015CommandSuggestionsPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        PacketDescriptor valid = descriptor();
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
        }

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, valid, codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), null, codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), valid, null, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), valid, codec, null));
        assertThrows(NullPointerException.class, () ->
                definition.rewrite(null, valid, rewriter()));
    }

    @Test
    void nativeCodecCoversRangesEntriesTooltipsAndUtf8() {
        ProtocolInfo<?> protocol = protocol();
        List<ClientboundCommandSuggestionsPacket> packets = List.of(
                new ClientboundCommandSuggestionsPacket(0, 0, 0, List.of()),
                new ClientboundCommandSuggestionsPacket(
                        27,
                        4,
                        3,
                        List.of(new ClientboundCommandSuggestionsPacket.Entry(
                                "über", Optional.empty()))),
                new ClientboundCommandSuggestionsPacket(
                        Integer.MAX_VALUE,
                        1,
                        5,
                        List.of(
                                new ClientboundCommandSuggestionsPacket.Entry(
                                        "first", Optional.of(Component.literal("First tooltip"))),
                                new ClientboundCommandSuggestionsPacket.Entry(
                                        "second", Optional.of(Component.literal("§aSecond tooltip"))))),
                new ClientboundCommandSuggestionsPacket(
                        Integer.MIN_VALUE,
                        -1,
                        -2,
                        List.of(new ClientboundCommandSuggestionsPacket.Entry(
                                "native signed values", Optional.empty()))));

        for (ClientboundCommandSuggestionsPacket packet : packets) {
            ByteBuf encoded = encode(protocol, packet);
            try {
                byte[] expectedWire = bytes(encoded);
                ByteBuf wireView = encoded.duplicate();
                assertEquals(0x0F, VarInt.read(wireView));

                ClientboundCommandSuggestionsPacket decoded = assertInstanceOf(
                        ClientboundCommandSuggestionsPacket.class, decode(protocol, encoded));
                assertFalse(encoded.isReadable());
                assertEquals(packet.id(), decoded.id());
                assertEquals(packet.start(), decoded.start());
                assertEquals(packet.length(), decoded.length());
                assertEquals(packet.suggestions().size(), decoded.suggestions().size());
                for (int index = 0; index < packet.suggestions().size(); index++) {
                    var expected = packet.suggestions().get(index);
                    var actual = decoded.suggestions().get(index);
                    assertEquals(expected.text(), actual.text());
                    assertEquals(expected.tooltip().isPresent(), actual.tooltip().isPresent());
                    if (expected.tooltip().isPresent()) {
                        assertEquals(
                                expected.tooltip().orElseThrow().getString(),
                                actual.tooltip().orElseThrow().getString());
                    }
                }

                ByteBuf reencoded = encode(protocol, decoded);
                try {
                    assertArrayEquals(expectedWire, bytes(reencoded));
                } finally {
                    reencoded.release();
                }
            } finally {
                encoded.release();
            }
        }
    }

    @Test
    void nativeCodecRejectsTruncationAndLeavesTrailingFramingBytes() {
        ProtocolInfo<?> protocol = protocol();
        for (byte[] payload : List.of(
                new byte[0],
                new byte[] {1},
                new byte[] {1, 0},
                new byte[] {1, 0, 0},
                new byte[] {1, 0, 0, 1},
                new byte[] {1, 0, 0, 1, 3, 'a'})) {
            ByteBuf malformed = Unpooled.buffer();
            try {
                VarInt.write(malformed, 0x0F);
                malformed.writeBytes(payload);
                assertThrows(RuntimeException.class, () -> decode(protocol, malformed));
            } finally {
                malformed.release();
            }
        }

        ByteBuf completeTooltip = encode(
                protocol,
                new ClientboundCommandSuggestionsPacket(
                        4,
                        0,
                        1,
                        List.of(new ClientboundCommandSuggestionsPacket.Entry(
                                "value", Optional.of(Component.literal("tooltip"))))));
        ByteBuf truncatedTooltip = completeTooltip.copy(
                completeTooltip.readerIndex(), completeTooltip.readableBytes() - 1);
        try {
            assertThrows(RuntimeException.class, () -> decode(protocol, truncatedTooltip));
        } finally {
            truncatedTooltip.release();
            completeTooltip.release();
        }

        ByteBuf trailing = encode(
                protocol, new ClientboundCommandSuggestionsPacket(3, 0, 0, List.of()));
        try {
            trailing.writeByte(0x7A);
            assertInstanceOf(ClientboundCommandSuggestionsPacket.class, decode(protocol, trailing));
            assertEquals(0x7A, trailing.readUnsignedByte());
            assertFalse(trailing.isReadable());
        } finally {
            trailing.release();
        }
    }

    private static PacketDescriptor descriptor() {
        return descriptor(
                TYPE_NAME,
                PACKET_ID,
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
                PacketDisposition.EPHEMERAL,
                clientbound,
                replayable,
                false,
                "paper-26.2/" + IDENTIFIER,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes, AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local command suggestions must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local command suggestions must not decode");
            }
        };
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
    }

    private static ByteBuf encode(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            StreamCodec<Object, Object> codec = (StreamCodec) protocol.codec();
            codec.encode(buffer, packet);
            return buffer;
        } catch (Throwable failure) {
            buffer.release();
            throw failure;
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] result = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), result);
        return result;
    }

    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        StreamCodec<Object, Object> codec = (StreamCodec) protocol.codec();
        return (Packet<?>) codec.decode(buffer);
    }
}
