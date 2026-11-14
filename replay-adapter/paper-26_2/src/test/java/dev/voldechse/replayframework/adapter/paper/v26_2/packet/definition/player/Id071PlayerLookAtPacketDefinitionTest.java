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
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id071PlayerLookAtPacketDefinitionTest {

    private static final int PACKET_ID = 0x47;
    private static final String TYPE_NAME = "play.clientbound.minecraft:player_look_at";
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
    void classifiesAndRegistersLookAtAsViewerLocalEphemeralData() {
        var definition = new Id071PlayerLookAtPacketDefinition();

        assertEquals(71, definition.packetId());
        assertEquals(PACKET_ID, Id071PlayerLookAtPacketDefinition.PACKET_ID);
        assertEquals("minecraft:player_look_at", Id071PlayerLookAtPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
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
        assertEquals(PacketDisposition.EPHEMERAL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
    }

    @Test
    void nativeCodecPreservesAbsolutePositionTargetsAndBothSourceAnchors() {
        ProtocolInfo<?> protocol = protocol();

        for (EntityAnchorArgument.Anchor fromAnchor : EntityAnchorArgument.Anchor.values()) {
            ClientboundPlayerLookAtPacket source = new ClientboundPlayerLookAtPacket(
                    fromAnchor, 12.5, -64.0, -0.25);
            ByteBuf encoded = encodePacket(protocol, source);
            try {
                byte[] expectedWire = bytes(encoded);
                assertEquals(PACKET_ID, VarInt.read(encoded));
                assertEquals(26, encoded.readableBytes());

                ClientboundPlayerLookAtPacket decoded = assertInstanceOf(
                        ClientboundPlayerLookAtPacket.class,
                        decodeFull(protocol, expectedWire));
                assertEquals(fromAnchor, decoded.getFromAnchor());
                assertEquals(new Vec3(12.5, -64.0, -0.25), decoded.getPosition(null));

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
    void nativeCodecPreservesEntityTargetsAndBothTargetAnchors() {
        ProtocolInfo<?> protocol = protocol();
        int[] targetEntityIds = {0, 927, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};

        for (EntityAnchorArgument.Anchor fromAnchor : EntityAnchorArgument.Anchor.values()) {
            for (EntityAnchorArgument.Anchor targetAnchor : EntityAnchorArgument.Anchor.values()) {
                for (int targetEntityId : targetEntityIds) {
                    // Decode the exact entity-target wire branch without creating a live world entity.
                    byte[] body = entityTargetBody(
                            fromAnchor, 11.25, -8.5, 4.75, targetEntityId, targetAnchor);
                    byte[] expectedWire = fullWire(body);
                    ClientboundPlayerLookAtPacket decoded = assertInstanceOf(
                            ClientboundPlayerLookAtPacket.class,
                            decodeFull(protocol, expectedWire));
                    assertEquals(fromAnchor, decoded.getFromAnchor());

                    ByteBuf reencoded = encodePacket(protocol, decoded);
                    try {
                        assertArrayEquals(expectedWire, bytes(reencoded));
                    } finally {
                        reencoded.release();
                    }
                }
            }
        }
    }

    @Test
    void dropsPositionEntityAndMalformedPayloadsBeforeCodecUse() {
        var definition = new Id071PlayerLookAtPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (EntityAnchorArgument.Anchor fromAnchor : EntityAnchorArgument.Anchor.values()) {
            ClientboundPlayerLookAtPacket position = new ClientboundPlayerLookAtPacket(
                    fromAnchor, 1.0, 2.0, 3.0);
            assertDropBeforeCodec(definition, codec, nativePayload(protocol(), position));
            for (EntityAnchorArgument.Anchor targetAnchor : EntityAnchorArgument.Anchor.values()) {
                assertDropBeforeCodec(
                        definition,
                        codec,
                        entityTargetBody(fromAnchor, 1.0, 2.0, 3.0, 123, targetAnchor));
            }
        }

        for (byte[] malformed : List.of(
                new byte[0],
                new byte[] {0},
                new byte[9],
                entityTargetBodyPrefix())) {
            assertDropBeforeCodec(definition, codec, malformed);
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsTheRecordedCameraDirective() {
        PacketRewriteResult result = new Id071PlayerLookAtPacketDefinition()
                .rewrite(new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
        assertEquals(
                "recorded connection state does not belong to the observer",
                result.reason());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id071PlayerLookAtPacketDefinition();
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

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundPlayerLookAtPacket source = new ClientboundPlayerLookAtPacket(
                EntityAnchorArgument.Anchor.EYES, -2.0, 70.0, 11.0);
        ByteBuf encoded = encodePacket(protocol, source);
        try {
            encoded.writeByte(0x7A);
            ClientboundPlayerLookAtPacket decoded = assertInstanceOf(
                    ClientboundPlayerLookAtPacket.class, decode(protocol, encoded));
            assertEquals(EntityAnchorArgument.Anchor.EYES, decoded.getFromAnchor());
            assertEquals(new Vec3(-2.0, 70.0, 11.0), decoded.getPosition(null));
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertDropBeforeCodec(
            Id071PlayerLookAtPacketDefinition definition,
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

    private static byte[] entityTargetBody(
            EntityAnchorArgument.Anchor fromAnchor,
            double x,
            double y,
            double z,
            int entityId,
            EntityAnchorArgument.Anchor targetAnchor) {
        FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer());
        try {
            output.writeEnum(fromAnchor);
            output.writeDouble(x);
            output.writeDouble(y);
            output.writeDouble(z);
            output.writeBoolean(true);
            output.writeVarInt(entityId);
            output.writeEnum(targetAnchor);
            return bytes(output);
        } finally {
            output.release();
        }
    }

    private static byte[] entityTargetBodyPrefix() {
        FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer());
        try {
            output.writeEnum(EntityAnchorArgument.Anchor.FEET);
            output.writeDouble(1.0);
            output.writeDouble(2.0);
            output.writeDouble(3.0);
            output.writeBoolean(true);
            return bytes(output);
        } finally {
            output.release();
        }
    }

    private static byte[] nativePayload(
            ProtocolInfo<?> protocol, ClientboundPlayerLookAtPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded));
            return bytes(encoded);
        } finally {
            encoded.release();
        }
    }

    private static byte[] fullWire(byte[] body) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(body);
            return bytes(wire);
        } finally {
            wire.release();
        }
    }

    private static ClientboundPlayerLookAtPacket decodeFull(
            ProtocolInfo<?> protocol, byte[] wireBytes) {
        ByteBuf wire = Unpooled.wrappedBuffer(wireBytes);
        try {
            return assertInstanceOf(ClientboundPlayerLookAtPacket.class, decode(protocol, wire));
        } finally {
            wire.release();
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
                PacketDisposition.EPHEMERAL,
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
                throw new AssertionError("viewer-local look-at must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local look-at must not decode");
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
}
