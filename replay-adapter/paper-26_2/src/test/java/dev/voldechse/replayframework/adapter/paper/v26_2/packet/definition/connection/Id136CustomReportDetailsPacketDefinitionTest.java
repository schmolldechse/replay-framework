package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomReportDetailsPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id136CustomReportDetailsPacketDefinitionTest {

    private static final int PACKET_ID = 136;
    private static final String TYPE_NAME = "play.clientbound.minecraft:custom_report_details";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000136");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000137");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesAndRegistersReportDetailsAsCapturedConnectionControl() {
        var definition = new Id136CustomReportDetailsPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x88, Id136CustomReportDetailsPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:custom_report_details",
                Id136CustomReportDetailsPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.CONTROL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertFalse(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.UNSAFE,
                definition.classification().playbackScope());

        var domainMatches = ConnectionPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id136CustomReportDetailsPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id136CustomReportDetailsPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.CONTROL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertFalse(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.UNSAFE, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void blocksValidAndMalformedFramesBeforePlaybackCodecAccess() {
        ProtocolInfo<?> protocol = protocol();
        byte[] validPayload = packetBody(
                protocol,
                new ClientboundCustomReportDetailsPacket(Map.of(
                        "server", "recorded.example",
                        "incident", "disconnect report metadata")));
        var definition = new Id136CustomReportDetailsPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : new byte[][] {
            validPayload,
            new byte[0],
            new byte[] {0},
            new byte[] {1, (byte) 0x80},
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07}
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
    void rejectsAlreadyDecodedReportDetailsWithoutInspectingOrRewritingThem() {
        var definition = new Id136CustomReportDetailsPacketDefinition();
        ClientboundCustomReportDetailsPacket packet = new ClientboundCustomReportDetailsPacket(
                Map.of("source", "recorded server"));

        IncompatibleAdapterException failure = assertThrows(
                IncompatibleAdapterException.class,
                () -> definition.rewrite(packet, descriptor(), rewriter()));

        assertEquals(
                "packet descriptor is not permitted for playback: " + TYPE_NAME,
                failure.getMessage());
    }

    @Test
    void nativeCodecRoundTripsEmptyUnicodeAndMaximumSizedDetailsMap() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundCustomReportDetailsPacket empty = nativeRoundTrip(protocol, Map.of());
        assertTrue(empty.details().isEmpty());

        Map<String, String> details = new LinkedHashMap<>();
        details.put("", "");
        details.put("é".repeat(128), "🧭".repeat(2_048));
        for (int index = 2; index < 32; index++) {
            details.put("server.detail." + index, "value-" + index);
        }

        ClientboundCustomReportDetailsPacket decoded = nativeRoundTrip(protocol, details);
        assertEquals(details, decoded.details());
        assertEquals(32, decoded.details().size());
    }

    @Test
    void nativeCodecRejectsMoreThan32EntriesAndKeysOrValuesPastTheirLimits() {
        ProtocolInfo<?> protocol = protocol();

        Map<String, String> tooManyDetails = new HashMap<>();
        for (int index = 0; index < 33; index++) {
            tooManyDetails.put("detail." + index, "value");
        }
        assertThrows(RuntimeException.class, () -> encodePacket(
                protocol, new ClientboundCustomReportDetailsPacket(tooManyDetails)));
        assertThrows(RuntimeException.class, () -> encodePacket(
                protocol,
                new ClientboundCustomReportDetailsPacket(Map.of("k".repeat(129), "value"))));
        assertThrows(RuntimeException.class, () -> encodePacket(
                protocol,
                new ClientboundCustomReportDetailsPacket(Map.of("key", "v".repeat(4_097)))));

        ByteBuf oversizedCount = Unpooled.buffer();
        try {
            VarInt.write(oversizedCount, PACKET_ID);
            VarInt.write(oversizedCount, 33);
            assertThrows(RuntimeException.class, () -> decode(protocol, oversizedCount));
        } finally {
            oversizedCount.release();
        }
    }

    @Test
    void nativeCodecRejectsEveryTruncatedBodyAndLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completePacket = packetWire(protocol, new ClientboundCustomReportDetailsPacket(
                Map.of("server", "example.org", "empty", "")));

        for (int length = 0; length < completePacket.length; length++) {
            ByteBuf prefix = Unpooled.wrappedBuffer(java.util.Arrays.copyOf(completePacket, length));
            try {
                assertThrows(RuntimeException.class, () -> decode(protocol, prefix));
            } finally {
                prefix.release();
            }
        }

        ByteBuf withFollowingByte = Unpooled.buffer();
        try {
            withFollowingByte.writeBytes(completePacket);
            withFollowingByte.writeByte(0x7A);
            ClientboundCustomReportDetailsPacket decoded = assertInstanceOf(
                    ClientboundCustomReportDetailsPacket.class, decode(protocol, withFollowingByte));
            assertEquals(Map.of("server", "example.org", "empty", ""), decoded.details());
            assertEquals(0x7A, withFollowingByte.readUnsignedByte());
            assertFalse(withFollowingByte.isReadable());
        } finally {
            withFollowingByte.release();
        }
    }

    @Test
    void rejectsDescriptorMismatchBeforePlaybackCodecUse() {
        var definition = new Id136CustomReportDetailsPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, new AtomicInteger());

        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(PACKET_ID + 1), codec, rewriter()));
        assertEquals(0, decodes.get());
    }

    private static ClientboundCustomReportDetailsPacket nativeRoundTrip(
            ProtocolInfo<?> protocol, Map<String, String> details) {
        ByteBuf encoded = encodePacket(protocol, new ClientboundCustomReportDetailsPacket(details));
        try {
            ByteBuf fields = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(fields));
            int entryCount = VarInt.read(fields);
            assertEquals(details.size(), entryCount);
            Map<String, String> decodedDetails = new HashMap<>();
            for (int index = 0; index < entryCount; index++) {
                decodedDetails.put(readString(fields), readString(fields));
            }
            assertEquals(details, decodedDetails);
            assertFalse(fields.isReadable());

            ClientboundCustomReportDetailsPacket decoded = assertInstanceOf(
                    ClientboundCustomReportDetailsPacket.class, decode(protocol, encoded));
            assertEquals(details, decoded.details());
            assertFalse(encoded.isReadable());
            return decoded;
        } finally {
            encoded.release();
        }
    }

    private static byte[] packetBody(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded));
            return bytes(encoded);
        } finally {
            encoded.release();
        }
    }

    private static byte[] packetWire(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            return bytes(encoded);
        } finally {
            encoded.release();
        }
    }

    private static ByteBuf encodePacket(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, packet);
            return encoded;
        } catch (RuntimeException | Error failure) {
            encoded.release();
            throw failure;
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] result = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), result);
        return result;
    }

    private static String readString(ByteBuf buffer) {
        int length = VarInt.read(buffer);
        byte[] utf8 = new byte[length];
        buffer.readBytes(utf8);
        return new String(utf8, StandardCharsets.UTF_8);
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes, AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("blocked report details must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("blocked report details must not decode");
            }
        };
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(PACKET_ID);
    }

    private static PacketDescriptor descriptor(int packetId) {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }
}
