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
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id073PlayerRotationPacketDefinitionTest {

    private static final int PACKET_ID = 0x49;
    private static final String TYPE_NAME = "play.clientbound.minecraft:player_rotation";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final float[][] ROTATIONS = {
        {0.0F, 0.0F},
        {-0.0F, 0.0F},
        {180.0F, -90.0F},
        {-180.0F, 90.0F},
        {Float.MIN_VALUE, -Float.MIN_VALUE},
        {Float.MAX_VALUE, -Float.MAX_VALUE},
        {Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY},
        {Float.NaN, Float.NaN}
    };

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesAndRegistersTheConnectionLocalRotationCorrection() {
        var definition = new Id073PlayerRotationPacketDefinition();

        assertEquals(73, definition.packetId());
        assertEquals(PACKET_ID, Id073PlayerRotationPacketDefinition.PACKET_ID);
        assertEquals("minecraft:player_rotation", Id073PlayerRotationPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());

        assertEquals(1, PlayerPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .count());
        assertEquals(1, Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .count());
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
    void nativeCodecRoundTripsBothRelativeFlagsAndRepresentativeAngles() {
        ProtocolInfo<?> protocol = protocol();

        for (int flags = 0; flags < 4; flags++) {
            boolean relativeYaw = (flags & 1) != 0;
            boolean relativePitch = (flags & 2) != 0;
            for (float[] rotation : ROTATIONS) {
                ClientboundPlayerRotationPacket source = new ClientboundPlayerRotationPacket(
                        rotation[0], relativeYaw, rotation[1], relativePitch);
                ByteBuf encoded = encodePacket(protocol, source);
                try {
                    byte[] expectedWire = bytes(encoded);
                    assertEquals(PACKET_ID, VarInt.read(encoded));
                    assertFloatBits(rotation[0], encoded.readFloat());
                    assertEquals(relativeYaw, encoded.readBoolean());
                    assertFloatBits(rotation[1], encoded.readFloat());
                    assertEquals(relativePitch, encoded.readBoolean());
                    assertFalse(encoded.isReadable());

                    ClientboundPlayerRotationPacket decoded = assertInstanceOf(
                            ClientboundPlayerRotationPacket.class, decode(protocol, expectedWire));
                    assertFloatBits(rotation[0], decoded.yRot());
                    assertEquals(relativeYaw, decoded.relativeY());
                    assertFloatBits(rotation[1], decoded.xRot());
                    assertEquals(relativePitch, decoded.relativeX());

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
        }
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundPlayerRotationPacket source = new ClientboundPlayerRotationPacket(
                45.0F, true, -30.0F, false);
        ByteBuf encoded = encodePacket(protocol, source);
        try {
            encoded.writeByte(0x7A);
            ClientboundPlayerRotationPacket decoded = assertInstanceOf(
                    ClientboundPlayerRotationPacket.class, decode(protocol, encoded));
            assertFloatBits(45.0F, decoded.yRot());
            assertTrue(decoded.relativeY());
            assertFloatBits(-30.0F, decoded.xRot());
            assertFalse(decoded.relativeX());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void dropsEveryFlagCombinationAndMalformedRotationBeforeCodecUse() {
        var definition = new Id073PlayerRotationPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        ProtocolInfo<?> protocol = protocol();

        for (int flags = 0; flags < 4; flags++) {
            ClientboundPlayerRotationPacket packet = new ClientboundPlayerRotationPacket(
                    75.0F,
                    (flags & 1) != 0,
                    -15.0F,
                    (flags & 2) != 0);
            assertDropBeforeCodec(definition, codec, nativePayload(protocol, packet));
        }
        for (byte[] malformed : List.of(
                new byte[0],
                new byte[] {0},
                new byte[] {0, 0, 0, 0},
                new byte[] {0, 0, 0, 0, 2, 0, 0, 0, 0},
                new byte[] {0, 0, 0, 0, 1, 0, 0, 0})) {
            assertDropBeforeCodec(definition, codec, malformed);
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsTheRecordedViewRotation() {
        PacketRewriteResult result = new Id073PlayerRotationPacketDefinition()
                .rewrite(new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
        assertEquals(
                "recorded connection state does not belong to the observer",
                result.reason());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id073PlayerRotationPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
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
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.rewrite(new Object(), mismatch, rewriter()));
        }
    }

    private static void assertDropBeforeCodec(
            Id073PlayerRotationPacketDefinition definition,
            PacketCodec<Object> codec,
            byte[] payload) {
        PacketRewriteResult result = definition.handlePlayback(
                frame(payload), descriptor(), codec, rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
        assertEquals(
                "recorded connection state does not belong to the observer",
                result.reason());
    }

    private static byte[] nativePayload(
            ProtocolInfo<?> protocol, ClientboundPlayerRotationPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
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

    private static ClientboundPlayerRotationPacket decode(
            ProtocolInfo<?> protocol, byte[] wireBytes) {
        ByteBuf wire = Unpooled.wrappedBuffer(wireBytes);
        try {
            return assertInstanceOf(ClientboundPlayerRotationPacket.class, decode(protocol, wire));
        } finally {
            wire.release();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static void assertFloatBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
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
        return new PacketDescriptor(
                typeName,
                phase,
                direction,
                packetId,
                PacketDisposition.STATEFUL,
                direction == PacketDescriptor.Direction.CLIENTBOUND,
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
                throw new AssertionError("viewer-local rotation must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local rotation must not decode");
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }
}
