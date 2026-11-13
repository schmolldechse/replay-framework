package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.chat;

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
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.Paper26_3PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
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
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id023CustomChatCompletionsPacketDefinitionTest {

    private static final String TYPE_NAME =
            "play.clientbound.minecraft:custom_chat_completions";
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
    void classifiesCustomChatCompletionsAsViewerLocalState() {
        var definition = new Id023CustomChatCompletionsPacketDefinition();

        assertEquals(23, definition.packetId());
        assertEquals(0x17, Id023CustomChatCompletionsPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:custom_chat_completions",
                Id023CustomChatCompletionsPacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInTheChatDomainAndCatalog() {
        var domainMatches = ChatPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_3PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(
                Id023CustomChatCompletionsPacketDefinition.class,
                domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(
                Id023CustomChatCompletionsPacketDefinition.class,
                catalogMatches.getFirst());
    }

    @Test
    void dropsEveryRecordedPayloadBeforeDecodeOrEncode() {
        var definition = new Id023CustomChatCompletionsPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0, 0},
            new byte[] {2, 1, 1, 'a'},
            new byte[] {3, 0},
            new byte[] {(byte) 0x80},
            new byte[] {0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                    (byte) 0xFF, 0x0F},
            new byte[] {0, 1, 2, (byte) 0xC3}
        }) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());

            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingDecodedType() {
        PacketRewriteResult result = new Id023CustomChatCompletionsPacketDefinition()
                .rewrite(new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void validatesNullAndDescriptorBoundariesBeforeApplyingLocalPolicy() {
        var definition = new Id023CustomChatCompletionsPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), null, codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), descriptor(), null, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), descriptor(), codec, null));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(24, true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(23, false, PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(23, true, PacketDescriptor.PlaybackScope.SAFE),
                codec, rewriter()));
    }

    @Test
    void nativeProtocolRoundTripsAllActionsAndOrderedEntries() {
        ProtocolInfo<?> protocol = protocol();

        assertRoundTrip(protocol, ClientboundCustomChatCompletionsPacket.Action.ADD, List.of());
        assertRoundTrip(
                protocol,
                ClientboundCustomChatCompletionsPacket.Action.REMOVE,
                List.of("alpha", "alpha", "two words"));
        assertRoundTrip(
                protocol,
                ClientboundCustomChatCompletionsPacket.Action.SET,
                List.of("Grüße", "雪", "emoji-😀"));
    }

    @Test
    void nativeProtocolPreservesRepeatedStateTransitionsAsIndependentPackets() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf first = encodePacket(protocol, new ClientboundCustomChatCompletionsPacket(
                ClientboundCustomChatCompletionsPacket.Action.ADD, List.of("one")));
        ByteBuf second = encodePacket(protocol, new ClientboundCustomChatCompletionsPacket(
                ClientboundCustomChatCompletionsPacket.Action.REMOVE, List.of("one")));
        ByteBuf third = encodePacket(protocol, new ClientboundCustomChatCompletionsPacket(
                ClientboundCustomChatCompletionsPacket.Action.SET, List.of("two")));
        try {
            assertPacket(decode(protocol, first),
                    ClientboundCustomChatCompletionsPacket.Action.ADD, List.of("one"));
            assertPacket(decode(protocol, second),
                    ClientboundCustomChatCompletionsPacket.Action.REMOVE, List.of("one"));
            assertPacket(decode(protocol, third),
                    ClientboundCustomChatCompletionsPacket.Action.SET, List.of("two"));
        } finally {
            first.release();
            second.release();
            third.release();
        }
    }

    @Test
    void nativeProtocolSupportsStringAndListVarIntBoundaries() {
        ProtocolInfo<?> protocol = protocol();
        for (String entry : new String[] {
            "a".repeat(127),
            "a".repeat(128),
            "a".repeat(16_383),
            "a".repeat(16_384),
            "é".repeat(32_767)
        }) {
            assertRoundTrip(
                    protocol,
                    ClientboundCustomChatCompletionsPacket.Action.SET,
                    List.of(entry));
        }

        assertRoundTrip(
                protocol,
                ClientboundCustomChatCompletionsPacket.Action.ADD,
                java.util.Collections.nCopies(128, "x"));
    }

    @Test
    void nativeProtocolMapsUnknownActionsToAdd() {
        ProtocolInfo<?> protocol = protocol();
        for (int actionId : new int[] {3, 127, -1, Integer.MAX_VALUE}) {
            ByteBuf input = Unpooled.buffer();
            try {
                writeVarInt(input, 23);
                writeVarInt(input, actionId);
                writeVarInt(input, 0);

                assertPacket(
                        decode(protocol, input),
                        ClientboundCustomChatCompletionsPacket.Action.ADD,
                        List.of());
                assertFalse(input.isReadable());
            } finally {
                input.release();
            }
        }
    }

    @Test
    void nativeProtocolRejectsMalformedBodies() {
        ProtocolInfo<?> protocol = protocol();

        for (byte[] body : new byte[][] {
            new byte[0],
            new byte[] {(byte) 0x80},
            new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80,
                    (byte) 0x80, (byte) 0x80, 0},
            new byte[] {0},
            new byte[] {0, 1},
            new byte[] {0, 1, 1},
            new byte[] {0, 1, 2, (byte) 0xC3},
            new byte[] {0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                    (byte) 0xFF, 0x0F}
        }) {
            assertMalformed(protocol, body);
        }
    }

    @Test
    void nativeProtocolRejectsStringsLongerThanTheMaximum() {
        ProtocolInfo<?> protocol = protocol();
        assertThrows(RuntimeException.class, () -> encodePacket(
                protocol,
                new ClientboundCustomChatCompletionsPacket(
                        ClientboundCustomChatCompletionsPacket.Action.SET,
                        List.of("a".repeat(32_768)))));
    }

    private static void assertRoundTrip(
            ProtocolInfo<?> protocol,
            ClientboundCustomChatCompletionsPacket.Action action,
            List<String> entries) {
        ByteBuf encoded = encodePacket(
                protocol, new ClientboundCustomChatCompletionsPacket(action, entries));
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(23, readVarInt(wire));
            assertEquals(action.ordinal(), readVarInt(wire));
            assertEquals(entries.size(), readVarInt(wire));
            for (String entry : entries) {
                assertEquals(entry, readUtf(wire));
            }
            assertFalse(wire.isReadable());

            assertPacket(decode(protocol, encoded), action, entries);
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertPacket(
            Packet<?> packet,
            ClientboundCustomChatCompletionsPacket.Action action,
            List<String> entries) {
        ClientboundCustomChatCompletionsPacket completions = assertInstanceOf(
                ClientboundCustomChatCompletionsPacket.class, packet);
        assertEquals(action, completions.action());
        assertEquals(entries, completions.entries());
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] body) {
        ByteBuf input = Unpooled.buffer();
        try {
            writeVarInt(input, 23);
            input.writeBytes(body);
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
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

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static Paper26_3ReplayPacketRewriter rewriter() {
        return new Paper26_3ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(23, true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            int packetId,
            boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                replayable,
                false,
                "paper-26.3/" + TYPE_NAME,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 23, payload);
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local custom completions must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local custom completions must not decode");
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

    private static String readUtf(ByteBuf buffer) {
        int length = readVarInt(buffer);
        String value = buffer.toString(buffer.readerIndex(), length, StandardCharsets.UTF_8);
        buffer.skipBytes(length);
        return value;
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
}
