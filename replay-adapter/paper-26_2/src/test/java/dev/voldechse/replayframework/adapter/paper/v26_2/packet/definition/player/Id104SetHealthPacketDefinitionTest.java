package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

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
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id104SetHealthPacketDefinitionTest {

    private static final int PACKET_ID = 104;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_health";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000141");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000142");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesHealthAsCapturedViewerLocalPlayerState() {
        var definition = new Id104SetHealthPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x68, Id104SetHealthPacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_health", Id104SetHealthPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());

        var domainMatches = PlayerPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id104SetHealthPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id104SetHealthPacketDefinition.class, catalogMatches.getFirst());

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
    void nativeProtocolWritesHealthFoodAndSaturationInFieldOrder() {
        ProtocolInfo<?> protocol = protocol();

        for (Vitals expected : List.of(
                new Vitals(20.0F, 20, 5.0F),
                new Vitals(0.0F, 0, 0.0F),
                new Vitals(8.5F, 7, 2.5F),
                new Vitals(-0.0F, Integer.MIN_VALUE, -0.0F),
                new Vitals(Float.NaN, Integer.MAX_VALUE, Float.POSITIVE_INFINITY),
                new Vitals(Float.NEGATIVE_INFINITY, -1, Float.NaN))) {
            assertNativeRoundTrip(protocol, expected);
        }
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedFieldPrefixAndOverlongFoodVarInt() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completeBody = body(protocol, packet(10.0F, 12, 3.0F));

        for (int length = 0; length < completeBody.length; length++) {
            assertNativeDecodeFails(protocol, java.util.Arrays.copyOf(completeBody, length));
        }

        ByteBuf overlongFood = Unpooled.buffer();
        try {
            VarInt.write(overlongFood, PACKET_ID);
            overlongFood.writeFloat(10.0F);
            overlongFood.writeBytes(new byte[] {
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0
            });
            assertThrows(RuntimeException.class, () -> decode(protocol, overlongFood));
        } finally {
            overlongFood.release();
        }
    }

    @Test
    void localPlaybackDropsValidAndMalformedPayloadsBeforePacketDecoding() {
        var definition = new Id104SetHealthPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        ProtocolInfo<?> protocol = protocol();

        for (byte[] payload : List.of(
                body(protocol, packet(20.0F, 20, 5.0F)),
                body(protocol, packet(0.0F, 0, 0.0F)),
                new byte[0],
                new byte[] {(byte) 0xFF},
                new byte[] {0, 0, 0, 0, (byte) 0x80, (byte) 0x80})) {
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
    void directRewriteDropsWithoutInspectingDecodedPacketFields() {
        PacketRewriteResult result = new Id104SetHealthPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id104SetHealthPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                        PacketDescriptor.Direction.SERVERBOUND, false,
                        PacketDescriptor.PlaybackScope.UNSAFE),
                descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                    frame(new byte[0]), mismatch, codec, rewriter()));
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    private static void assertNativeRoundTrip(ProtocolInfo<?> protocol, Vitals expected) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, packet(expected.health(), expected.food(), expected.saturation()));
            byte[] expectedWire = bytes(encoded);

            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(Float.floatToIntBits(expected.health()),
                    Float.floatToIntBits(fields.readFloat()));
            assertEquals(expected.food(), fields.readVarInt());
            assertEquals(Float.floatToIntBits(expected.saturation()),
                    Float.floatToIntBits(fields.readFloat()));
            assertFalse(fields.isReadable());

            ClientboundSetHealthPacket decoded = assertInstanceOf(
                    ClientboundSetHealthPacket.class, decode(protocol, encoded));
            assertFalse(encoded.isReadable());
            assertEquals(Float.floatToIntBits(expected.health()),
                    Float.floatToIntBits(decoded.getHealth()));
            assertEquals(expected.food(), decoded.getFood());
            assertEquals(Float.floatToIntBits(expected.saturation()),
                    Float.floatToIntBits(decoded.getSaturation()));

            ByteBuf reencoded = Unpooled.buffer();
            try {
                encode(protocol, reencoded, decoded);
                assertArrayEquals(expectedWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static void assertNativeDecodeFails(ProtocolInfo<?> protocol, byte[] body) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(body);
            assertThrows(RuntimeException.class, () -> decode(protocol, wire));
        } finally {
            wire.release();
        }
    }

    private static byte[] body(ProtocolInfo<?> protocol, ClientboundSetHealthPacket packet) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, packet);
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            return bytes(fields);
        } finally {
            encoded.release();
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static ClientboundSetHealthPacket packet(float health, int food, float saturation) {
        return new ClientboundSetHealthPacket(health, food, saturation);
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
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
                throw new AssertionError("viewer-local set_health must not encode");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local set_health must not decode");
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

    private record Vitals(float health, int food, float saturation) {}
}
