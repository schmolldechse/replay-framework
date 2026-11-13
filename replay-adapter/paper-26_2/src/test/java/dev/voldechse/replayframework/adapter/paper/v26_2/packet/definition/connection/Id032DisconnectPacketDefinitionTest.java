package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
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
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Style;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id032DisconnectPacketDefinitionTest {

    private static final int PACKET_ID = 32;
    private static final String TYPE_NAME = "play.clientbound.minecraft:disconnect";
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
    void classifiesDisconnectAsUnsafeConnectionControl() {
        var definition = new Id032DisconnectPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x20, Id032DisconnectPacketDefinition.PACKET_ID);
        assertEquals("minecraft:disconnect", Id032DisconnectPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.CONTROL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertFalse(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.UNSAFE,
                definition.classification().playbackScope());
    }

    @Test
    void registersExactlyOnceInConnectionDomainCatalogAndPaperRegistry() {
        var domainMatches = ConnectionPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id032DisconnectPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id032DisconnectPacketDefinition.class, catalogMatches.getFirst());

        var registryDescriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, registryDescriptor.typeName());
        assertEquals(PacketDisposition.CONTROL, registryDescriptor.disposition());
        assertFalse(registryDescriptor.replayable());
        assertFalse(registryDescriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.UNSAFE, registryDescriptor.playbackScope());
    }

    @Test
    void blocksEveryRecordedPayloadBeforePlaybackCodecAccess() {
        var definition = new Id032DisconnectPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[] {10},
            new byte[] {(byte) 0x80},
            new byte[] {1, 2, 3, 4}
        }) {
            IncompatibleAdapterException failure = assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(frame(payload), descriptor(), codec, rewriter()));
            assertEquals(
                    "packet descriptor is not permitted for playback: " + TYPE_NAME,
                    failure.getMessage());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void rejectsAlreadyDecodedValuesBeforeInvokingPacketSpecificRewrite() {
        IncompatibleAdapterException failure = assertThrows(
                IncompatibleAdapterException.class,
                () -> new Id032DisconnectPacketDefinition()
                        .rewrite(new Object(), descriptor(), rewriter()));

        assertEquals(
                "packet descriptor is not permitted for playback: " + TYPE_NAME,
                failure.getMessage());
    }

    @Test
    void validatesNullAndMismatchedDescriptorsBeforeAnyCodecAccess() {
        var definition = new Id032DisconnectPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, new AtomicInteger());

        assertThrows(NullPointerException.class, () -> definition.handlePlayback(
                null, descriptor(), codec, rewriter()));
        assertThrows(NullPointerException.class, () -> definition.handlePlayback(
                frame(new byte[0]), null, codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(), null, rewriter()));

        PacketDescriptor wrongId = new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                PACKET_ID + 1,
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.UNSAFE);
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), wrongId, codec, rewriter()));

        assertEquals(0, decodes.get());
    }

    @Test
    void nativeProtocolRoundTripsAllComponentContentFamiliesAndTreeShapes() {
        ProtocolInfo<?> protocol = protocol();

        for (String json : List.of(
                "\"\"",
                "\"Disconnected: Grüße, 雪, 😀\"",
                "{\"text\":\"Kick\",\"color\":\"gold\",\"bold\":true,\"extra\":[{\"text\":\" now\",\"italic\":true}]}",
                "[\"First\",{\"text\":\" second\"}]",
                "{\"translate\":\"disconnect.genericReason\",\"fallback\":\"Disconnected\",\"with\":[\"maintenance\",{\"text\":\"soon\"}]}",
                "{\"keybind\":\"key.jump\"}",
                "{\"score\":{\"name\":\"@s\",\"objective\":\"sidebar\"}}",
                "{\"selector\":\"@e[type=minecraft:zombie,limit=1]\",\"separator\":{\"text\":\", \"}}",
                "{\"nbt\":\"Items[].id\",\"plain\":true,\"storage\":\"minecraft:replay_reason\"}",
                "{\"nbt\":\"Pos[0]\",\"interpret\":true,\"block\":\"~ ~ ~\"}",
                "{\"nbt\":\"Health\",\"entity\":\"@s\"}",
                "{\"atlas\":\"minecraft:blocks\",\"sprite\":\"minecraft:stone\",\"fallback\":\"stone\"}",
                "{\"player\":{\"name\":\"Notch\"},\"hat\":false}")) {
            assertRoundTrip(protocol, decodeComponent(json));
        }

        Component styledTree = Component.literal("Connection closed")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(true))
                .append(Component.translatableWithFallback(
                                "replay.disconnect.detail", "Reason: %s", "maintenance")
                        .withStyle(Style.EMPTY.withUnderlined(true)));
        assertRoundTrip(protocol, styledTree);
        assertRoundTrip(protocol, Component.literal("a".repeat(32_767)));
    }

    @Test
    void nativeProtocolRejectsMissingAndTruncatedReasonComponents() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, new byte[0]);
        assertMalformed(protocol, new byte[] {0});
        assertMalformed(protocol, new byte[] {10});

        ByteBuf complete = encodePacket(protocol,
                new ClientboundDisconnectPacket(Component.translatableWithFallback(
                        "disconnect.reason", "Disconnected: %s", "test")));
        try {
            ByteBuf payload = complete.duplicate();
            assertEquals(PACKET_ID, readVarInt(payload));
            byte[] payloadBytes = new byte[payload.readableBytes()];
            payload.readBytes(payloadBytes);
            for (int length = 0; length < payloadBytes.length; length++) {
                assertMalformed(protocol, java.util.Arrays.copyOf(payloadBytes, length));
            }
        } finally {
            complete.release();
        }
    }

    private static void assertRoundTrip(ProtocolInfo<?> protocol, Component expectedReason) {
        ByteBuf encoded = encodePacket(protocol, new ClientboundDisconnectPacket(expectedReason));
        try {
            ByteBuf header = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(header));

            ClientboundDisconnectPacket decoded = assertInstanceOf(
                    ClientboundDisconnectPacket.class, decode(protocol, encoded));
            assertEquals(expectedReason, decoded.reason());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static Component decodeComponent(String json) {
        return ComponentSerialization.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow();
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf input = Unpooled.buffer();
        try {
            writeVarInt(input, PACKET_ID);
            input.writeBytes(payload);
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

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                PACKET_ID,
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.UNSAFE);
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
                throw new AssertionError("disconnect control packet must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("disconnect control packet must not decode");
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
