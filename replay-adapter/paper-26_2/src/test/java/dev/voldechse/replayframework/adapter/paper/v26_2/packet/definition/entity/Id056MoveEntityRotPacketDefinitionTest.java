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
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id056MoveEntityRotPacketDefinitionTest {

    private static final int PACKET_ID = 56;
    private static final String TYPE_NAME = "play.clientbound.minecraft:move_entity_rot";
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
    void classifiesRelativeRotationAsEphemeralIdentityBearingTimelineData() {
        var definition = new Id056MoveEntityRotPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x38, Id056MoveEntityRotPacketDefinition.PACKET_ID);
        assertEquals("minecraft:move_entity_rot", Id056MoveEntityRotPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
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
        assertInstanceOf(Id056MoveEntityRotPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id056MoveEntityRotPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                descriptor.playbackScope());
    }

    @Test
    void rewritesOnlyEntityIdAndPreservesRawRotationsAndGroundState() {
        var definition = new Id056MoveEntityRotPacketDefinition();
        var context = rewriter();
        int mappedEntityId = context.mapEntityId(300);
        byte[][] rotations = {
            {Byte.MIN_VALUE, Byte.MAX_VALUE},
            {(byte) -1, (byte) 0},
            {(byte) 0, (byte) 1},
            {(byte) 1, (byte) -1}
        };

        for (boolean onGround : new boolean[] {false, true}) {
            for (byte[] rotation : rotations) {
                var original = packet(300, rotation[0], rotation[1], onGround);
                var read = definition.read(original);
                assertEquals(new Id056MoveEntityRotPacketDefinition.Value(
                        300, rotation[0], rotation[1], onGround), read);

                var rewritten = definition.rewrite(read, context).value().orElseThrow();
                assertEquals(new Id056MoveEntityRotPacketDefinition.Value(
                        mappedEntityId, rotation[0], rotation[1], onGround), rewritten);
                assertEquals(
                        new WireFields(mappedEntityId, rotation[0], rotation[1], onGround),
                        readWireFields(definition.rebuild(rewritten)));
            }
        }
        assertNotEquals(300, mappedEntityId);
    }

    @Test
    void preservesEveryPossibleSignedRotationByte() {
        var definition = new Id056MoveEntityRotPacketDefinition();
        for (int rawRotation = Byte.MIN_VALUE; rawRotation <= Byte.MAX_VALUE; rawRotation++) {
            byte yRot = (byte) rawRotation;
            byte xRot = (byte) ~rawRotation;
            boolean onGround = (rawRotation & 1) == 0;
            var original = packet(300, yRot, xRot, onGround);

            var value = definition.read(original);
            assertEquals(yRot, value.yRot());
            assertEquals(xRot, value.xRot());
            assertEquals(onGround, value.onGround());
            assertEquals(new WireFields(300, yRot, xRot, onGround),
                    readWireFields(definition.rebuild(value)));
        }
    }

    @Test
    void playbackRewritesAndEncodesTheRebuiltRotationPacket() {
        var definition = new Id056MoveEntityRotPacketDefinition();
        var context = rewriter();
        var original = packet(401, Byte.MIN_VALUE, Byte.MAX_VALUE, true);
        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        ClientboundMoveEntityPacket.Rot rewritten = assertInstanceOf(
                ClientboundMoveEntityPacket.Rot.class, result.packet().orElseThrow());
        assertEquals(
                new WireFields(context.mapEntityId(401), Byte.MIN_VALUE, Byte.MAX_VALUE, true),
                readWireFields(rewritten));
        assertEquals(PACKET_ID,
                definition.encode(rewritten, descriptor(), codec(rewritten)).packetId());
    }

    @Test
    void validatesDescriptorBeforePlaybackAndEncoding() {
        var definition = new Id056MoveEntityRotPacketDefinition();
        ClientboundMoveEntityPacket.Rot original = packet(402, (byte) 4, (byte) 5, false);

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

    @Test
    void nativeProtocolUsesIdFiftySixAndEncodesFieldsInOrder() {
        ProtocolInfo<?> protocol = protocol();
        for (boolean onGround : new boolean[] {false, true}) {
            ClientboundMoveEntityPacket.Rot expected =
                    packet(300, Byte.MIN_VALUE, Byte.MAX_VALUE, onGround);
            ByteBuf encoded = encodePacket(protocol, expected);
            try {
                byte[] expectedWire = bytes(encoded);
                ByteBuf wire = encoded.duplicate();
                assertEquals(PACKET_ID, readVarInt(wire));
                assertEquals(300, readVarInt(wire));
                assertEquals(Byte.MIN_VALUE, wire.readByte());
                assertEquals(Byte.MAX_VALUE, wire.readByte());
                assertEquals(onGround, wire.readBoolean());
                assertFalse(wire.isReadable());

                ClientboundMoveEntityPacket.Rot decoded = assertInstanceOf(
                        ClientboundMoveEntityPacket.Rot.class, decode(protocol, encoded));
                assertEquals(new WireFields(300, Byte.MIN_VALUE, Byte.MAX_VALUE, onGround),
                        readWireFields(decoded));
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

    @Test
    void nativeProtocolRejectsEveryTruncatedPacketBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, new byte[0]);
        assertMalformed(protocol, new byte[] {
            (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0
        });

        ByteBuf complete = encodePacket(protocol, packet(300, (byte) 1, (byte) 2, true));
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
        ByteBuf encoded = encodePacket(protocol, packet(300, (byte) 7, (byte) 8, true));
        try {
            encoded.writeByte(0x7A);
            assertInstanceOf(ClientboundMoveEntityPacket.Rot.class, decode(protocol, encoded));
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static ClientboundMoveEntityPacket.Rot packet(
            int entityId, byte yRot, byte xRot, boolean onGround) {
        return new ClientboundMoveEntityPacket.Rot(entityId, yRot, xRot, onGround);
    }

    private static WireFields readWireFields(ClientboundMoveEntityPacket.Rot packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            ClientboundMoveEntityPacket.Rot.STREAM_CODEC.encode(
                    new FriendlyByteBuf(buffer), packet);
            return new WireFields(
                    readVarInt(buffer), buffer.readByte(), buffer.readByte(), buffer.readBoolean());
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
                PacketDisposition.EPHEMERAL,
                true,
                replayable,
                false,
                "paper-26.2/" + TYPE_NAME,
                scope);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundMoveEntityPacket.Rot decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundMoveEntityPacket.Rot.class, packet);
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

    private record WireFields(int entityId, byte yRot, byte xRot, boolean onGround) {}
}
