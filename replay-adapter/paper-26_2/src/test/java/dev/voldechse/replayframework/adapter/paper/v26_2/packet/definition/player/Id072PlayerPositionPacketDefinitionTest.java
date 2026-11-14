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
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id072PlayerPositionPacketDefinitionTest {

    private static final int PACKET_ID = 0x48;
    private static final String TYPE_NAME = "play.clientbound.minecraft:player_position";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final int ALL_RELATIVE_FLAGS = (1 << Relative.values().length) - 1;
    private static final int[] TELEPORT_IDS = {
        0, 1, 127, 128, 255, Integer.MAX_VALUE - 1
    };
    private static final PositionMoveRotation CHANGE = new PositionMoveRotation(
            new Vec3(12.25, -64.5, -0.0),
            new Vec3(-0.0, 0.375, -128.125),
            179.5F,
            -90.0F);

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesAndRegistersTheConnectionLocalPositionCorrection() {
        var definition = new Id072PlayerPositionPacketDefinition();

        assertEquals(72, definition.packetId());
        assertEquals(PACKET_ID, Id072PlayerPositionPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:player_position",
                Id072PlayerPositionPacketDefinition.IDENTIFIER);
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
    void nativeCodecRoundTripsEveryCombinationOfTheNineRelativeFlags() {
        ProtocolInfo<?> protocol = protocol();

        assertEquals(9, Relative.values().length);
        for (int flags = 0; flags <= ALL_RELATIVE_FLAGS; flags++) {
            int teleportId = TELEPORT_IDS[flags % TELEPORT_IDS.length];
            ClientboundPlayerPositionPacket source = packet(teleportId, flags);
            ByteBuf encoded = encodePacket(protocol, source);
            try {
                byte[] expectedWire = bytes(encoded);
                assertEquals(PACKET_ID, VarInt.read(encoded));
                assertEquals(teleportId, VarInt.read(encoded));
                assertDoubleBits(CHANGE.position().x, encoded.readDouble());
                assertDoubleBits(CHANGE.position().y, encoded.readDouble());
                assertDoubleBits(CHANGE.position().z, encoded.readDouble());
                assertDoubleBits(CHANGE.deltaMovement().x, encoded.readDouble());
                assertDoubleBits(CHANGE.deltaMovement().y, encoded.readDouble());
                assertDoubleBits(CHANGE.deltaMovement().z, encoded.readDouble());
                assertFloatBits(CHANGE.yRot(), encoded.readFloat());
                assertFloatBits(CHANGE.xRot(), encoded.readFloat());
                assertEquals(flags, encoded.readInt());
                assertFalse(encoded.isReadable());

                ClientboundPlayerPositionPacket decoded = assertInstanceOf(
                        ClientboundPlayerPositionPacket.class, decode(protocol, expectedWire));
                assertEquals(teleportId, decoded.id());
                assertPositionMoveRotation(CHANGE, decoded.change());
                assertEquals(Relative.unpack(flags), decoded.relatives());

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

    @Test
    void nativeCodecIgnoresUndefinedRelativeBitsAndKeepsPacketFraming() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol, packet(128, ALL_RELATIVE_FLAGS));
        try {
            encoded.setInt(encoded.writerIndex() - Integer.BYTES, -1);
            encoded.writeByte(0x7A);

            ClientboundPlayerPositionPacket decoded = assertInstanceOf(
                    ClientboundPlayerPositionPacket.class,
                    decode(protocol, encoded));
            assertEquals(128, decoded.id());
            assertEquals(Relative.ALL, decoded.relatives());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                reencoded.readerIndex(reencoded.writerIndex() - Integer.BYTES);
                assertEquals(ALL_RELATIVE_FLAGS, reencoded.readInt());
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    @Test
    void dropsEveryRelativeCombinationAndMalformedCorrectionBeforeCodecUse() {
        var definition = new Id072PlayerPositionPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        ProtocolInfo<?> protocol = protocol();

        for (int flags = 0; flags <= ALL_RELATIVE_FLAGS; flags++) {
            byte[] payload = nativePayload(protocol, packet(TELEPORT_IDS[flags % TELEPORT_IDS.length], flags));
            assertDropBeforeCodec(definition, codec, payload);
        }

        for (byte[] malformed : List.of(
                new byte[0],
                new byte[] {(byte) 0x80},
                new byte[] {0},
                new byte[] {0, 0},
                new byte[] {0, 0, 1, 2, 3, 4, 5},
                truncate(nativePayload(protocol, packet(7, ALL_RELATIVE_FLAGS)), 11))) {
            assertDropBeforeCodec(definition, codec, malformed);
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsTheRecordedPositionCorrection() {
        PacketRewriteResult result = new Id072PlayerPositionPacketDefinition()
                .rewrite(new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
        assertEquals(
                "recorded connection state does not belong to the observer",
                result.reason());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id072PlayerPositionPacketDefinition();
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
            Id072PlayerPositionPacketDefinition definition,
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

    private static ClientboundPlayerPositionPacket packet(int teleportId, int flags) {
        return new ClientboundPlayerPositionPacket(
                teleportId, CHANGE, Relative.unpack(flags));
    }

    private static void assertPositionMoveRotation(
            PositionMoveRotation expected, PositionMoveRotation actual) {
        assertDoubleBits(expected.position().x, actual.position().x);
        assertDoubleBits(expected.position().y, actual.position().y);
        assertDoubleBits(expected.position().z, actual.position().z);
        assertDoubleBits(expected.deltaMovement().x, actual.deltaMovement().x);
        assertDoubleBits(expected.deltaMovement().y, actual.deltaMovement().y);
        assertDoubleBits(expected.deltaMovement().z, actual.deltaMovement().z);
        assertFloatBits(expected.yRot(), actual.yRot());
        assertFloatBits(expected.xRot(), actual.xRot());
    }

    private static void assertDoubleBits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    private static void assertFloatBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }

    private static byte[] nativePayload(
            ProtocolInfo<?> protocol, ClientboundPlayerPositionPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded));
            return bytes(encoded);
        } finally {
            encoded.release();
        }
    }

    private static byte[] truncate(byte[] payload, int length) {
        return java.util.Arrays.copyOf(payload, length);
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

    private static ClientboundPlayerPositionPacket decode(
            ProtocolInfo<?> protocol, byte[] wireBytes) {
        ByteBuf wire = Unpooled.wrappedBuffer(wireBytes);
        try {
            return assertInstanceOf(ClientboundPlayerPositionPacket.class, decode(protocol, wire));
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
                throw new AssertionError("viewer-local position must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local position must not decode");
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }
}
