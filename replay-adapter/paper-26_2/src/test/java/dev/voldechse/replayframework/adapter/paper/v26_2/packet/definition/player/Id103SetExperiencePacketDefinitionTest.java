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
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id103SetExperiencePacketDefinitionTest {

    private static final int PACKET_ID = 103;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_experience";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000131");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000132");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesExperienceAsCapturedViewerLocalPlayerState() {
        var definition = new Id103SetExperiencePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x67, Id103SetExperiencePacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_experience", Id103SetExperiencePacketDefinition.IDENTIFIER);
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
        assertInstanceOf(Id103SetExperiencePacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id103SetExperiencePacketDefinition.class, catalogMatches.getFirst());

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
    void nativeProtocolWritesProgressThenLevelAndTotalAsVarInts() {
        ProtocolInfo<?> protocol = protocol();

        for (ExperienceFields fields : List.of(
                new ExperienceFields(0.0F, 0, 0),
                new ExperienceFields(0.375F, 8, 33),
                new ExperienceFields(1.0F, Integer.MAX_VALUE, Integer.MAX_VALUE),
                new ExperienceFields(-0.0F, Integer.MIN_VALUE, -1),
                new ExperienceFields(Float.NaN, Integer.MAX_VALUE, Integer.MIN_VALUE),
                new ExperienceFields(Float.POSITIVE_INFINITY, -128, 128),
                new ExperienceFields(Float.NEGATIVE_INFINITY, 127, -127))) {
            assertNativeRoundTrip(protocol, fields);
        }
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedFieldPrefixAndOverlongVarInt() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completeBody = body(protocol, packet(0.625F, 12, 144));

        for (int length = 0; length < completeBody.length; length++) {
            assertNativeDecodeFails(protocol, java.util.Arrays.copyOf(completeBody, length));
        }

        ByteBuf overlongLevel = Unpooled.buffer();
        try {
            VarInt.write(overlongLevel, PACKET_ID);
            overlongLevel.writeFloat(0.5F);
            overlongLevel.writeBytes(new byte[] {
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0
            });
            assertThrows(RuntimeException.class, () -> decode(protocol, overlongLevel));
        } finally {
            overlongLevel.release();
        }
    }

    @Test
    void localPlaybackDropsValidAndMalformedPayloadsBeforePacketDecoding() {
        var definition = new Id103SetExperiencePacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        ProtocolInfo<?> protocol = protocol();

        for (byte[] payload : List.of(
                body(protocol, packet(0.5F, 6, 72)),
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
        PacketRewriteResult result = new Id103SetExperiencePacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id103SetExperiencePacketDefinition();
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

    private static void assertNativeRoundTrip(ProtocolInfo<?> protocol, ExperienceFields expected) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, packet(expected.progress(), expected.level(), expected.total()));
            byte[] expectedWire = bytes(encoded);

            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(Float.floatToIntBits(expected.progress()),
                    Float.floatToIntBits(fields.readFloat()));
            assertEquals(expected.level(), fields.readVarInt());
            assertEquals(expected.total(), fields.readVarInt());
            assertFalse(fields.isReadable());

            ClientboundSetExperiencePacket decoded = assertInstanceOf(
                    ClientboundSetExperiencePacket.class, decode(protocol, encoded));
            assertFalse(encoded.isReadable());
            assertEquals(Float.floatToIntBits(expected.progress()),
                    Float.floatToIntBits(decoded.getExperienceProgress()));
            assertEquals(expected.level(), decoded.getExperienceLevel());
            assertEquals(expected.total(), decoded.getTotalExperience());

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

    private static byte[] body(ProtocolInfo<?> protocol, ClientboundSetExperiencePacket packet) {
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

    private static ClientboundSetExperiencePacket packet(float progress, int level, int total) {
        return new ClientboundSetExperiencePacket(progress, total, level);
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
                throw new AssertionError("viewer-local set_experience must not encode");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local set_experience must not decode");
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

    private record ExperienceFields(float progress, int level, int total) {}
}
