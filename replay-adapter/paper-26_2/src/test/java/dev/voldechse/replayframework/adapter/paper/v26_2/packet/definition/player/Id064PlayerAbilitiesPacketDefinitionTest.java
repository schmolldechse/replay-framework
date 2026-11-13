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
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.entity.player.Abilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id064PlayerAbilitiesPacketDefinitionTest {

    private static final int PACKET_ID = 0x40;
    private static final String TYPE_NAME = "play.clientbound.minecraft:player_abilities";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final float[][] SPEEDS = {
        {0.0F, 0.0F},
        {-0.0F, 0.1F},
        {Float.MIN_VALUE, -Float.MAX_VALUE},
        {Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY},
        {Float.NaN, 0.25F}
    };

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesAndRegistersOnlyViewerLocalAbilities() {
        var definition = new Id064PlayerAbilitiesPacketDefinition();

        assertEquals(64, definition.packetId());
        assertEquals(PACKET_ID, Id064PlayerAbilitiesPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:player_abilities",
                Id064PlayerAbilitiesPacketDefinition.IDENTIFIER);
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
    void dropsEveryAbilityFlagCombinationAndMalformedPayloadBeforeCodecUse() {
        var definition = new Id064PlayerAbilitiesPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (int flags = 0; flags < 16; flags++) {
            byte[] payload = nativePayload(packet(flags, SPEEDS[flags % SPEEDS.length]));
            assertDropBeforeCodec(definition, codec, payload);
        }
        for (byte[] malformed : List.of(
                new byte[0],
                new byte[] {0},
                new byte[] {(byte) 0x80},
                new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8})) {
            assertDropBeforeCodec(definition, codec, malformed);
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingTheDecodedType() {
        PacketRewriteResult result = new Id064PlayerAbilitiesPacketDefinition()
                .rewrite(new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id064PlayerAbilitiesPacketDefinition();
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
            assertThrows(IncompatibleAdapterException.class, () ->
                    definition.rewrite(new Object(), mismatch, rewriter()));
        }
    }

    @Test
    void nativeProtocolEncodesAllFlagBitsAndSpeedFieldsInWireOrder() {
        ProtocolInfo<?> protocol = protocol();

        for (int flags = 0; flags < 16; flags++) {
            float[] speeds = SPEEDS[flags % SPEEDS.length];
            ClientboundPlayerAbilitiesPacket expected = packet(flags, speeds);
            ByteBuf encoded = encodePacket(protocol, expected);
            try {
                byte[] expectedWire = bytes(encoded);
                ByteBuf wireView = encoded.duplicate();
                assertEquals(PACKET_ID, VarInt.read(wireView));
                assertEquals(flags, wireView.readUnsignedByte());
                assertFloatBits(speeds[0], wireView.readFloat());
                assertFloatBits(speeds[1], wireView.readFloat());
                assertEquals(0, wireView.readableBytes());
                assertEquals(1 + 1 + Float.BYTES + Float.BYTES, expectedWire.length);

                ClientboundPlayerAbilitiesPacket decoded = assertInstanceOf(
                        ClientboundPlayerAbilitiesPacket.class, decode(protocol, encoded));
                assertFalse(encoded.isReadable());
                assertAbilities(flags, speeds, decoded);

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
    void nativeDecoderIgnoresFlagsOutsideTheFourDefinedBits() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = Unpooled.buffer();
        try {
            VarInt.write(encoded, PACKET_ID);
            encoded.writeByte(0xF0);
            encoded.writeFloat(0.375F);
            encoded.writeFloat(-0.125F);

            ClientboundPlayerAbilitiesPacket decoded = assertInstanceOf(
                    ClientboundPlayerAbilitiesPacket.class, decode(protocol, encoded));
            assertFalse(decoded.isInvulnerable());
            assertFalse(decoded.isFlying());
            assertFalse(decoded.canFly());
            assertFalse(decoded.canInstabuild());
            assertFloatBits(0.375F, decoded.getFlyingSpeed());
            assertFloatBits(-0.125F, decoded.getWalkingSpeed());
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertEquals(PACKET_ID, VarInt.read(reencoded));
                assertEquals(0, reencoded.readUnsignedByte());
                assertFloatBits(0.375F, reencoded.readFloat());
                assertFloatBits(-0.125F, reencoded.readFloat());
                assertFalse(reencoded.isReadable());
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedPacketBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completeBody = nativePayload(packet(0x0F, SPEEDS[1]));
        assertEquals(1 + Float.BYTES + Float.BYTES, completeBody.length);

        for (int length = 0; length < completeBody.length; length++) {
            ByteBuf malformed = Unpooled.buffer();
            try {
                VarInt.write(malformed, PACKET_ID);
                malformed.writeBytes(completeBody, 0, length);
                assertThrows(RuntimeException.class, () -> decode(protocol, malformed));
            } finally {
                malformed.release();
            }
        }
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundPlayerAbilitiesPacket original = packet(0x0D, SPEEDS[2]);
        ByteBuf encoded = encodePacket(protocol, original);
        try {
            encoded.writeByte(0x7A);
            ClientboundPlayerAbilitiesPacket decoded = assertInstanceOf(
                    ClientboundPlayerAbilitiesPacket.class, decode(protocol, encoded));
            assertAbilities(0x0D, SPEEDS[2], decoded);
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertDropBeforeCodec(
            Id064PlayerAbilitiesPacketDefinition definition,
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

    private static ClientboundPlayerAbilitiesPacket packet(int flags, float[] speeds) {
        Abilities abilities = new Abilities();
        abilities.invulnerable = (flags & 0x01) != 0;
        abilities.flying = (flags & 0x02) != 0;
        abilities.mayfly = (flags & 0x04) != 0;
        abilities.instabuild = (flags & 0x08) != 0;
        abilities.setFlyingSpeed(speeds[0]);
        abilities.setWalkingSpeed(speeds[1]);
        return new ClientboundPlayerAbilitiesPacket(abilities);
    }

    private static void assertAbilities(
            int flags, float[] speeds, ClientboundPlayerAbilitiesPacket packet) {
        assertEquals((flags & 0x01) != 0, packet.isInvulnerable());
        assertEquals((flags & 0x02) != 0, packet.isFlying());
        assertEquals((flags & 0x04) != 0, packet.canFly());
        assertEquals((flags & 0x08) != 0, packet.canInstabuild());
        assertFloatBits(speeds[0], packet.getFlyingSpeed());
        assertFloatBits(speeds[1], packet.getWalkingSpeed());
    }

    private static void assertFloatBits(float expected, float actual) {
        assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(actual));
    }

    private static byte[] nativePayload(ClientboundPlayerAbilitiesPacket packet) {
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
                throw new AssertionError("viewer-local player abilities must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local player abilities must not decode");
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
