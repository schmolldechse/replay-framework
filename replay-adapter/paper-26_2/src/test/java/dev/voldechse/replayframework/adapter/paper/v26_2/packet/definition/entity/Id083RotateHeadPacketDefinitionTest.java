package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2CheckpointEncoder;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id083RotateHeadPacketDefinitionTest {

    private static final int PACKET_ID = 83;
    private static final String TYPE_NAME = "play.clientbound.minecraft:rotate_head";
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
    void classifiesAbsoluteHeadRotationAsCheckpointRelevantIdentityBearingSceneState() {
        var definition = new Id083RotateHeadPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x53, Id083RotateHeadPacketDefinition.PACKET_ID);
        assertEquals("minecraft:rotate_head", Id083RotateHeadPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
        assertEquals(
                Paper26_2CheckpointEncoder.CheckpointPacketFamily.ENTITY_METADATA,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));
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
        assertInstanceOf(Id083RotateHeadPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id083RotateHeadPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                descriptor.playbackScope());
    }

    @Test
    void rewritesOnlyEntityIdentityAndPreservesTheAbsoluteHeadYawByte() {
        var definition = new Id083RotateHeadPacketDefinition();
        var context = rewriter();
        int mappedEntityId = context.mapEntityId(300);

        for (int rawYaw = 0; rawYaw <= 0xFF; rawYaw++) {
            byte headYaw = (byte) rawYaw;
            ClientboundRotateHeadPacket original = packet(300, headYaw);

            var value = definition.read(original);
            assertEquals(new Id083RotateHeadPacketDefinition.Value(300, headYaw), value);

            var rewritten = definition.rewrite(value, context).value().orElseThrow();
            assertEquals(
                    new Id083RotateHeadPacketDefinition.Value(mappedEntityId, headYaw), rewritten);
            assertEquals(new WireFields(mappedEntityId, headYaw),
                    readWireFields(definition.rebuild(rewritten)));
        }
        assertNotEquals(300, mappedEntityId);
    }

    @Test
    void nativeProtocolUsesIdEightyThreeAndPreservesEntityVarIntBoundaries() {
        ProtocolInfo<?> protocol = protocol();
        int[] entityIds = {
            0,
            1,
            127,
            128,
            16_383,
            16_384,
            Integer.MAX_VALUE,
            Integer.MIN_VALUE,
            -1
        };
        int[] rawYaws = {0, 1, 127, 128, 254, 255};

        for (int entityId : entityIds) {
            for (int rawYaw : rawYaws) {
                byte yaw = (byte) rawYaw;
                ClientboundRotateHeadPacket expected = packet(entityId, yaw);
                ByteBuf encoded = encodePacket(protocol, expected);
                try {
                    byte[] expectedWire = bytes(encoded);
                    ByteBuf wire = encoded.duplicate();
                    assertEquals(PACKET_ID, readVarInt(wire));
                    assertEquals(entityId, readVarInt(wire));
                    assertEquals(yaw, wire.readByte());
                    assertFalse(wire.isReadable());

                    ClientboundRotateHeadPacket decoded = assertInstanceOf(
                            ClientboundRotateHeadPacket.class, decode(protocol, encoded));
                    assertEquals(new WireFields(entityId, yaw), readWireFields(decoded));
                    assertFalse(encoded.isReadable());

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
    void nativeProtocolRejectsEveryTruncatedBodyPrefixAndInvalidEntityVarInt() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, new byte[0]);
        assertMalformed(protocol, new byte[] {(byte) 0x80});
        assertMalformed(protocol, new byte[] {1});
        assertMalformed(protocol, new byte[] {
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            0,
            1
        });

        ByteBuf complete = encodePacket(protocol, packet(300, (byte) 42));
        try {
            ByteBuf wire = complete.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            byte[] payload = bytes(wire);
            for (int length = 0; length < payload.length; length++) {
                assertMalformed(protocol, java.util.Arrays.copyOf(payload, length));
            }
        } finally {
            complete.release();
        }
    }

    @Test
    void nativeProtocolLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol, packet(300, (byte) 99));
        try {
            encoded.writeByte(0x7A);
            assertInstanceOf(ClientboundRotateHeadPacket.class, decode(protocol, encoded));
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void playbackRewritesAndEncodesTheRebuiltHeadRotationPacket() {
        var definition = new Id083RotateHeadPacketDefinition();
        var context = rewriter();
        ClientboundRotateHeadPacket original = packet(401, (byte) 0xA5);

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        ClientboundRotateHeadPacket rewritten = assertInstanceOf(
                ClientboundRotateHeadPacket.class, result.packet().orElseThrow());
        assertEquals(new WireFields(context.mapEntityId(401), (byte) 0xA5),
                readWireFields(rewritten));
        assertEquals(PACKET_ID,
                definition.encode(rewritten, descriptor(), codec(rewritten)).packetId());
    }

    @Test
    void validatesDescriptorBeforePlaybackAndEncoding() {
        var definition = new Id083RotateHeadPacketDefinition();
        ClientboundRotateHeadPacket original = packet(402, (byte) 4);

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec(original), rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(),
                descriptor(PACKET_ID + 1, true, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                codec(original),
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(),
                descriptor(PACKET_ID, true, PacketDescriptor.PlaybackScope.SAFE),
                codec(original),
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.encode(
                original,
                descriptor(PACKET_ID, false, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                codec(original)));
    }

    private static ClientboundRotateHeadPacket packet(int entityId, byte headYaw) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(entityId);
            buffer.writeByte(headYaw);
            return ClientboundRotateHeadPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static WireFields readWireFields(ClientboundRotateHeadPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            ClientboundRotateHeadPacket.STREAM_CODEC.encode(new FriendlyByteBuf(buffer), packet);
            return new WireFields(readVarInt(buffer), buffer.readByte());
        } finally {
            buffer.release();
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
        return descriptor(PACKET_ID, true, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static PacketDescriptor descriptor(
            int packetId, boolean replayable, PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                replayable,
                true,
                "paper-26.2/" + TYPE_NAME,
                scope);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundRotateHeadPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundRotateHeadPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        PACKET_ID,
                        new byte[0]);
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                return decoded;
            }
        };
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf input = Unpooled.buffer();
        try {
            writeVarInt(input, PACKET_ID);
            input.writeBytes(payload);
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
        }
    }

    private static int readVarInt(ByteBuf buffer) {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            int current = buffer.readUnsignedByte();
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("VarInt exceeds five bytes");
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            buffer.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buffer.writeByte(value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }

    private record WireFields(int entityId, byte headYaw) {}
}
