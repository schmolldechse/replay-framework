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
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id115SetTitlesAnimationPacketDefinitionTest {

    private static final int PACKET_ID = 115;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_titles_animation";
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
    void classifiesTitleTimingAsStatefulViewerLocalUiState() {
        var definition = new Id115SetTitlesAnimationPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x73, Id115SetTitlesAnimationPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:set_titles_animation",
                Id115SetTitlesAnimationPacketDefinition.IDENTIFIER);
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
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id115SetTitlesAnimationPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id115SetTitlesAnimationPacketDefinition.class, catalogMatches.getFirst());

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
    void nativeProtocolRoundTripsAllTimingValuesWithoutNormalization() {
        ProtocolInfo<?> protocol = protocol();
        List<int[]> timings = List.of(
                new int[] {0, 0, 0},
                new int[] {1, 20, 1},
                new int[] {10, 70, 20},
                new int[] {-1, Integer.MIN_VALUE, Integer.MAX_VALUE},
                new int[] {Integer.MAX_VALUE, 0, Integer.MIN_VALUE});

        for (int[] timing : timings) {
            assertNativeRoundTrip(
                    protocol,
                    new ClientboundSetTitlesAnimationPacket(timing[0], timing[1], timing[2]));
        }
    }

    @Test
    void nativeProtocolEncodesExactlyThreeFixedWidthIntsInWireOrder() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundSetTitlesAnimationPacket expected =
                new ClientboundSetTitlesAnimationPacket(0x01020304, 0x11121314, 0x21222324);
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            assertEquals(13, encoded.readableBytes());
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(0x01020304, wire.readInt());
            assertEquals(0x11121314, wire.readInt());
            assertEquals(0x21222324, wire.readInt());
            assertFalse(wire.isReadable());

            byte[] originalWire = bytes(encoded);
            ClientboundSetTitlesAnimationPacket decoded = assertInstanceOf(
                    ClientboundSetTitlesAnimationPacket.class, decode(protocol, encoded));
            assertEquals(expected.getFadeIn(), decoded.getFadeIn());
            assertEquals(expected.getStay(), decoded.getStay());
            assertEquals(expected.getFadeOut(), decoded.getFadeOut());
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

    @Test
    void dropsValidAndMalformedTimingsBeforeCodecAccess() {
        var definition = new Id115SetTitlesAnimationPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<byte[]> payloads = List.of(
                nativePayload(0, 0, 0),
                nativePayload(10, 70, 20),
                nativePayload(Integer.MIN_VALUE, -1, Integer.MAX_VALUE),
                new byte[0],
                new byte[] {0},
                new byte[11],
                new byte[13]);

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
    void directRewriteDropsWithoutInspectingTheDecodedPacket() {
        PacketRewriteResult result = new Id115SetTitlesAnimationPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id115SetTitlesAnimationPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            frame(new byte[0]), mismatch, codec, rewriter()));
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedTimingTuple() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(
                protocol, new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        try {
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            byte[] body = bytes(wire);
            assertEquals(12, body.length);
            for (int length = 0; length < body.length; length++) {
                assertMalformed(protocol, java.util.Arrays.copyOf(body, length));
            }
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeProtocolLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(
                protocol, new ClientboundSetTitlesAnimationPacket(5, 40, 7));
        try {
            encoded.writeByte(0x7A);
            ClientboundSetTitlesAnimationPacket decoded = assertInstanceOf(
                    ClientboundSetTitlesAnimationPacket.class, decode(protocol, encoded));
            assertEquals(5, decoded.getFadeIn());
            assertEquals(40, decoded.getStay());
            assertEquals(7, decoded.getFadeOut());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundSetTitlesAnimationPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] originalWire = bytes(encoded);
            ClientboundSetTitlesAnimationPacket decoded = assertInstanceOf(
                    ClientboundSetTitlesAnimationPacket.class, decode(protocol, encoded));
            assertEquals(expected.getFadeIn(), decoded.getFadeIn());
            assertEquals(expected.getStay(), decoded.getStay());
            assertEquals(expected.getFadeOut(), decoded.getFadeOut());
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

    private static byte[] nativePayload(int fadeIn, int stay, int fadeOut) {
        ByteBuf encoded = encodePacket(
                protocol(), new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
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
                throw new AssertionError("viewer-local packet must not be encoded");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local packet must not be decoded");
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
}
