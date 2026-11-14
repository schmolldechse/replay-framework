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
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id100SetEntityLinkPacketDefinitionTest {

    private static final int PACKET_ID = 100;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_entity_link";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesLeashLinksAsCheckpointRelevantIdentityBearingState() {
        var definition = new Id100SetEntityLinkPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x64, Id100SetEntityLinkPacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_entity_link", Id100SetEntityLinkPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
        assertEquals(Paper26_2CheckpointEncoder.CheckpointPacketFamily.PASSENGERS,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));

        var domainMatches = EntityPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id100SetEntityLinkPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id100SetEntityLinkPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                descriptor.playbackScope());
    }

    @Test
    void nativeCodecUsesThePacketIdAndTwoFixedWidthIntegersInFieldOrder() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundSetEntityLinkPacket expected = packet(0x01020304, 0xA1B2C3D4);
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] expectedWire = bytes(encoded);
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(8, fields.readableBytes());
            assertEquals(0x01020304, fields.readInt());
            assertEquals(0xA1B2C3D4, fields.readInt());
            assertFalse(fields.isReadable());

            ClientboundSetEntityLinkPacket decoded = assertInstanceOf(
                    ClientboundSetEntityLinkPacket.class, decode(protocol, encoded));
            assertEquals(0x01020304, decoded.getSourceId());
            assertEquals(0xA1B2C3D4, decoded.getDestId());
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

    @Test
    void nativeCodecPreservesSignedIntegerBoundariesAndNegativeNonzeroDestinations() {
        ProtocolInfo<?> protocol = protocol();

        assertNativeRoundTrip(protocol, packet(Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertNativeRoundTrip(protocol, packet(0, -1));
    }

    @Test
    void rewriteMapsBothEntityIdsForALeashRelationship() {
        var definition = new Id100SetEntityLinkPacketDefinition();
        var context = rewriter();
        int attachedEntityId = 1201;
        int holdingEntityId = 1202;

        var rewritten = definition.rewrite(
                new Id100SetEntityLinkPacketDefinition.Value(attachedEntityId, holdingEntityId),
                context).value().orElseThrow();

        assertEquals(context.mapEntityId(attachedEntityId), rewritten.sourceId());
        assertEquals(context.mapEntityId(holdingEntityId), rewritten.destinationId());
        assertNotEquals(attachedEntityId, rewritten.sourceId());
        assertNotEquals(holdingEntityId, rewritten.destinationId());
        assertNotEquals(rewritten.sourceId(), rewritten.destinationId());
    }

    @Test
    void rewriteMapsTheAttachedEntityAndPreservesZeroAsTheUnlinkSentinel() {
        var definition = new Id100SetEntityLinkPacketDefinition();
        var context = rewriter();
        int attachedEntityId = 1301;

        var rewritten = definition.rewrite(
                new Id100SetEntityLinkPacketDefinition.Value(attachedEntityId, 0),
                context).value().orElseThrow();

        assertEquals(context.mapEntityId(attachedEntityId), rewritten.sourceId());
        assertEquals(0, rewritten.destinationId());
        assertNotEquals(attachedEntityId, rewritten.sourceId());
    }

    @Test
    void rewriteMapsNegativeNonzeroDestinationsBecauseZeroIsTheVersionedSentinel() {
        var definition = new Id100SetEntityLinkPacketDefinition();
        var context = rewriter();
        int recordedDestinationId = -1;

        var rewritten = definition.rewrite(
                new Id100SetEntityLinkPacketDefinition.Value(1304, recordedDestinationId),
                context).value().orElseThrow();

        assertEquals(context.mapEntityId(recordedDestinationId), rewritten.destinationId());
        assertNotEquals(recordedDestinationId, rewritten.destinationId());
        assertNotEquals(0, rewritten.destinationId());
    }

    @Test
    void playbackRewritesLinkedAndUnlinkedPacketsBeforeEncoding() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id100SetEntityLinkPacketDefinition();
        var context = rewriter();
        PacketCodec<Object> codec = codec(protocol);

        assertRewrittenPlayback(definition, context, codec, protocol, 1401, 1402);
        assertRewrittenPlayback(definition, context, codec, protocol, 1403, 0);
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedFieldPrefix() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completeBody = body(protocol, packet(22, 23));

        assertEquals(8, completeBody.length);
        for (int length = 0; length < completeBody.length; length++) {
            assertNativeDecodeFails(protocol, java.util.Arrays.copyOf(completeBody, length));
        }
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeInt(33);
            wire.writeInt(44);
            wire.writeByte(0x7A);

            ClientboundSetEntityLinkPacket decoded = assertInstanceOf(
                    ClientboundSetEntityLinkPacket.class, decode(protocol, wire));

            assertEquals(33, decoded.getSourceId());
            assertEquals(44, decoded.getDestId());
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void validatesTheDescriptorBeforeDecodingOrEncoding() {
        var definition = new Id100SetEntityLinkPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes);
        var packet = packet(2, 3);

        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(TYPE_NAME, PACKET_ID + 1, true), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor("play.clientbound.minecraft:other", PACKET_ID, true),
                codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(TYPE_NAME, PACKET_ID, false), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.encode(
                packet, descriptor(TYPE_NAME, PACKET_ID + 1, true), codec));
        assertEquals(0, decodes.get());
    }

    private static void assertRewrittenPlayback(
            Id100SetEntityLinkPacketDefinition definition,
            Paper26_2ReplayPacketRewriter context,
            PacketCodec<Object> codec,
            ProtocolInfo<?> protocol,
            int sourceId,
            int destinationId) {
        byte[] payload = body(protocol, packet(sourceId, destinationId));
        PacketRewriteResult result = definition.handlePlayback(
                frame(payload), descriptor(), codec, context);
        ClientboundSetEntityLinkPacket rebuilt = assertInstanceOf(
                ClientboundSetEntityLinkPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(context.mapEntityId(sourceId), rebuilt.getSourceId());
        assertEquals(destinationId == 0 ? 0 : context.mapEntityId(destinationId),
                rebuilt.getDestId());

        PacketCodec.EncodedPacket encoded = definition.encode(rebuilt, descriptor(), codec);
        assertEquals(PACKET_ID, encoded.packetId());
        ClientboundSetEntityLinkPacket decoded = assertInstanceOf(
                ClientboundSetEntityLinkPacket.class,
                decodePayload(protocol, encoded.payload()));
        assertEquals(rebuilt.getSourceId(), decoded.getSourceId());
        assertEquals(rebuilt.getDestId(), decoded.getDestId());
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundSetEntityLinkPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] expectedWire = bytes(encoded);
            ClientboundSetEntityLinkPacket decoded = assertInstanceOf(
                    ClientboundSetEntityLinkPacket.class, decode(protocol, encoded));
            assertEquals(expected.getSourceId(), decoded.getSourceId());
            assertEquals(expected.getDestId(), decoded.getDestId());

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

    private static ClientboundSetEntityLinkPacket packet(int sourceId, int destinationId) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeInt(sourceId);
            buffer.writeInt(destinationId);
            return ClientboundSetEntityLinkPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static byte[] body(ProtocolInfo<?> protocol, ClientboundSetEntityLinkPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            return bytes(fields);
        } finally {
            encoded.release();
        }
    }

    private static Object decodePayload(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(payload);
            return decode(protocol, wire);
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
        return descriptor(TYPE_NAME, PACKET_ID, true);
    }

    private static PacketDescriptor descriptor(String typeName, int packetId, boolean replayable) {
        return new PacketDescriptor(
                typeName,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                replayable,
                true,
                "paper-26.2/" + typeName,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> codec(ProtocolInfo<?> protocol) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                ClientboundSetEntityLinkPacket nativePacket = assertInstanceOf(
                        ClientboundSetEntityLinkPacket.class, packet);
                ByteBuf wire = encodePacket(protocol, nativePacket);
                try {
                    FriendlyByteBuf fields = new FriendlyByteBuf(wire.duplicate());
                    assertEquals(PACKET_ID, fields.readVarInt());
                    return new EncodedPacket(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            PACKET_ID,
                            bytes(fields));
                } finally {
                    wire.release();
                }
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                return decodePayload(protocol, frame.payload());
            }
        };
    }

    private static PacketCodec<Object> failingCodec(AtomicInteger decodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                throw new AssertionError("descriptor mismatch must not encode");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("descriptor mismatch must not decode");
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
