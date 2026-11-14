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
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id093SetCameraPacketDefinitionTest {

    private static final int PACKET_ID = 93;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_camera";
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
    void classifiesCameraTargetAsStatefulViewerLocalData() {
        var definition = new Id093SetCameraPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x5D, Id093SetCameraPacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_camera", Id093SetCameraPacketDefinition.IDENTIFIER);
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
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void nativeCodecUsesOneVarIntForAllSignedCameraEntityIds() {
        ProtocolInfo<?> protocol = protocol();

        for (int cameraEntityId : new int[] {
            Integer.MIN_VALUE, -1, 0, 1, 127, 128, 16_383, 16_384, Integer.MAX_VALUE
        }) {
            byte[] expectedWire = packetWire(cameraEntityId);
            ClientboundSetCameraPacket decoded = decode(protocol, expectedWire);
            ByteBuf encoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(expectedWire, bytes(encoded));
            } finally {
                encoded.release();
            }
        }
    }

    @Test
    void nativeCodecCanonicalizesOverlongCameraEntityVarInts() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf overlong = Unpooled.buffer();
        try {
            VarInt.write(overlong, PACKET_ID);
            overlong.writeByte(0x80);
            overlong.writeByte(0x00);

            ClientboundSetCameraPacket decoded = assertInstanceOf(
                    ClientboundSetCameraPacket.class, decode(protocol, overlong));
            ByteBuf canonical = encodePacket(protocol, decoded);
            try {
                FriendlyByteBuf encoded = new FriendlyByteBuf(canonical.duplicate());
                assertEquals(PACKET_ID, encoded.readVarInt());
                assertEquals(0, encoded.readVarInt());
                assertFalse(encoded.isReadable());
            } finally {
                canonical.release();
            }
        } finally {
            overlong.release();
        }
    }

    @Test
    void nativeDecoderLeavesTheNextPacketByteUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = Unpooled.buffer();
        try {
            wire.writeBytes(packetWire(312));
            wire.writeByte(0x7A);

            assertInstanceOf(ClientboundSetCameraPacket.class, decode(protocol, wire));
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void dropsCameraChangesAndMalformedPayloadsBeforeUsingThePacketCodec() {
        var definition = new Id093SetCameraPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (int cameraEntityId : new int[] {0, 1, 312, -1, Integer.MAX_VALUE}) {
            assertDropBeforeCodec(definition, codec, packetPayload(cameraEntityId));
        }
        for (byte[] malformed : List.of(
                new byte[0],
                new byte[] {(byte) 0x80},
                new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x08},
                new byte[] {0x01, 0x02})) {
            assertDropBeforeCodec(definition, codec, malformed);
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsTheRecordedCameraTarget() {
        PacketRewriteResult result = new Id093SetCameraPacketDefinition()
                .rewrite(new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
        assertEquals(
                "recorded connection state does not belong to the observer",
                result.reason());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id093SetCameraPacketDefinition();
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
            Id093SetCameraPacketDefinition definition,
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

    private static byte[] packetWire(int cameraEntityId) {
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            wire.writeVarInt(PACKET_ID);
            wire.writeVarInt(cameraEntityId);
            return bytes(wire);
        } finally {
            wire.release();
        }
    }

    private static byte[] packetPayload(int cameraEntityId) {
        ByteBuf wire = Unpooled.wrappedBuffer(packetWire(cameraEntityId));
        try {
            assertEquals(PACKET_ID, VarInt.read(wire));
            return bytes(wire);
        } finally {
            wire.release();
        }
    }

    private static ClientboundSetCameraPacket decode(
            ProtocolInfo<?> protocol, byte[] wireBytes) {
        ByteBuf wire = Unpooled.wrappedBuffer(wireBytes);
        try {
            return assertInstanceOf(ClientboundSetCameraPacket.class, decode(protocol, wire));
        } finally {
            wire.release();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }

    private static ByteBuf encodePacket(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            ((StreamCodec) protocol.codec()).encode(buffer, packet);
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
                throw new AssertionError("viewer-local camera target must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local camera target must not decode");
            }
        };
    }
}
