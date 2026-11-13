package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

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
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id057MoveVehiclePacketDefinitionTest {

    private static final int PACKET_ID = 57;
    private static final String TYPE_NAME = "play.clientbound.minecraft:move_vehicle";
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
    void classifiesRecordedVehicleCorrectionAsViewerLocalEphemeralData() {
        var definition = new Id057MoveVehiclePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x39, Id057MoveVehiclePacketDefinition.PACKET_ID);
        assertEquals("minecraft:move_vehicle", Id057MoveVehiclePacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInEntityDomainCatalogAndPaperRegistry() {
        var domainMatches = EntityPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id057MoveVehiclePacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id057MoveVehiclePacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void dropsValidAndArbitraryPayloadsBeforeCodecDecodeOrEncode() {
        var definition = new Id057MoveVehiclePacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<byte[]> payloads = List.of(
                new byte[0],
                nativePayload(packet(new Vec3(12.5, -64.0, 300.25), 90.0F, -45.0F)),
                new byte[] {1},
                new byte[31],
                new byte[33],
                new byte[] {(byte) 0xFF, (byte) 0x80, 0});

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
        PacketRewriteResult result = new Id057MoveVehiclePacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingLocalPolicy() {
        var definition = new Id057MoveVehiclePacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            frame(new byte[0]), mismatch, codec, rewriter()));
        }
    }

    @Test
    void nativeProtocolRoundTripsPositionAndBothRotationFloatsVerbatim() {
        ProtocolInfo<?> protocol = protocol();
        List<ClientboundMoveVehiclePacket> packets = List.of(
                packet(new Vec3(12.5, -64.0, 300.25), 90.0F, -45.0F),
                packet(new Vec3(-0.0D, Double.MAX_VALUE, -Double.MAX_VALUE),
                        Float.MIN_VALUE, -Float.MAX_VALUE),
                packet(new Vec3(
                                Double.longBitsToDouble(0x7ff8000000001234L),
                                Double.POSITIVE_INFINITY,
                                Double.NEGATIVE_INFINITY),
                        Float.intBitsToFloat(0x7fc12345),
                        Float.NEGATIVE_INFINITY));

        for (ClientboundMoveVehiclePacket expected : packets) {
            ByteBuf encoded = encodePacket(protocol, expected);
            try {
                byte[] originalWire = bytes(encoded);
                ByteBuf wire = encoded.duplicate();
                assertEquals(PACKET_ID, VarInt.read(wire));
                assertFieldsMatch(expected, wire);
                assertFalse(wire.isReadable());

                ClientboundMoveVehiclePacket decoded = assertInstanceOf(
                        ClientboundMoveVehiclePacket.class, decode(protocol, encoded));
                assertFalse(encoded.isReadable());
                assertPacketBitsMatch(expected, decoded);

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
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPayloadPrefix() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, new byte[0]);

        ByteBuf encoded = encodePacket(
                protocol, packet(new Vec3(1.0, 2.0, 3.0), 4.0F, 5.0F));
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            byte[] payload = bytes(wire);
            for (int length = 0; length < payload.length; length++) {
                assertMalformed(protocol, java.util.Arrays.copyOf(payload, length));
            }
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeProtocolLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(
                protocol, packet(new Vec3(-1.0, 2.0, -3.0), 4.0F, -5.0F));
        try {
            encoded.writeByte(0x7A);
            assertInstanceOf(ClientboundMoveVehiclePacket.class, decode(protocol, encoded));
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static ClientboundMoveVehiclePacket packet(Vec3 position, float yRot, float xRot) {
        return new ClientboundMoveVehiclePacket(position, yRot, xRot);
    }

    private static void assertFieldsMatch(ClientboundMoveVehiclePacket packet, ByteBuf wire) {
        assertEquals(Double.doubleToRawLongBits(packet.position().x),
                Double.doubleToRawLongBits(wire.readDouble()));
        assertEquals(Double.doubleToRawLongBits(packet.position().y),
                Double.doubleToRawLongBits(wire.readDouble()));
        assertEquals(Double.doubleToRawLongBits(packet.position().z),
                Double.doubleToRawLongBits(wire.readDouble()));
        assertEquals(Float.floatToRawIntBits(packet.yRot()),
                Float.floatToRawIntBits(wire.readFloat()));
        assertEquals(Float.floatToRawIntBits(packet.xRot()),
                Float.floatToRawIntBits(wire.readFloat()));
    }

    private static void assertPacketBitsMatch(
            ClientboundMoveVehiclePacket expected, ClientboundMoveVehiclePacket actual) {
        assertEquals(Double.doubleToRawLongBits(expected.position().x),
                Double.doubleToRawLongBits(actual.position().x));
        assertEquals(Double.doubleToRawLongBits(expected.position().y),
                Double.doubleToRawLongBits(actual.position().y));
        assertEquals(Double.doubleToRawLongBits(expected.position().z),
                Double.doubleToRawLongBits(actual.position().z));
        assertEquals(Float.floatToRawIntBits(expected.yRot()),
                Float.floatToRawIntBits(actual.yRot()));
        assertEquals(Float.floatToRawIntBits(expected.xRot()),
                Float.floatToRawIntBits(actual.xRot()));
    }

    private static byte[] nativePayload(ClientboundMoveVehiclePacket packet) {
        ByteBuf encoded = encodePacket(protocol(), packet);
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
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
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
                PacketDisposition.EPHEMERAL,
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
                throw new AssertionError("viewer-local vehicle correction must not encode");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local vehicle correction must not decode");
            }
        };
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }
}
