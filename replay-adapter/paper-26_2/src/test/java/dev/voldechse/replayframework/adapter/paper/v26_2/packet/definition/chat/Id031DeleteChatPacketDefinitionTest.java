package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDeleteChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id031DeleteChatPacketDefinitionTest {

    private static final int PACKET_ID = 31;
    private static final String TYPE_NAME = "play.clientbound.minecraft:delete_chat";
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
    void classifiesDeleteChatAsViewerLocalEphemeralData() {
        var definition = new Id031DeleteChatPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x1F, Id031DeleteChatPacketDefinition.PACKET_ID);
        assertEquals("minecraft:delete_chat", Id031DeleteChatPacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInChatDomainCatalogAndPaperRegistry() {
        var domainMatches = ChatPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id031DeleteChatPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id031DeleteChatPacketDefinition.class, catalogMatches.getFirst());

        var registryDescriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, registryDescriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, registryDescriptor.disposition());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, registryDescriptor.playbackScope());
    }

    @Test
    void dropsRecordedFramesBeforeDecodingOrEncodingAnyPackedSignatureForm() {
        var definition = new Id031DeleteChatPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        byte[] fullSignature = new byte[MessageSignature.BYTES];

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {1}, // packed cache reference 0
            new byte[] {(byte) 0x80, 0x01}, // packed cache reference 127
            new byte[] {0}, // full-signature sentinel, intentionally truncated
            withFullSignature(fullSignature),
            new byte[] {(byte) 0x80} // malformed VarInt
        }) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());

            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingDecodedPacketType() {
        PacketRewriteResult result = new Id031DeleteChatPacketDefinition()
                .rewrite(new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void validatesNullAndDescriptorBoundariesBeforeApplyingLocalPolicy() {
        var definition = new Id031DeleteChatPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), null, codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), descriptor(), null, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), descriptor(), codec, null));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(PACKET_ID + 1, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(PACKET_ID, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(PACKET_ID, true,
                        PacketDescriptor.PlaybackScope.SAFE), codec, rewriter()));
    }

    @Test
    void nativeProtocolRoundTripsAllCacheReferencesAndTheFullSignatureForm() {
        ProtocolInfo<?> protocol = protocol();

        // A per-connection signature cache has 128 slots; test every valid index,
        // including both boundaries where the packed VarInt changes width.
        for (int cacheIndex = 0; cacheIndex < 128; cacheIndex++) {
            assertRoundTrip(protocol, new MessageSignature.Packed(cacheIndex));
        }

        byte[] signature = new byte[MessageSignature.BYTES];
        for (int index = 0; index < signature.length; index++) {
            signature[index] = (byte) (index * 37 + 11);
        }
        assertRoundTrip(protocol, new MessageSignature.Packed(new MessageSignature(signature)));
    }

    @Test
    void nativeProtocolRejectsMissingPackedValueAndTruncatedFullSignature() {
        ProtocolInfo<?> protocol = protocol();

        assertMalformed(protocol, new byte[0]);
        assertMalformed(protocol, new byte[] {(byte) 0x80});
        assertMalformed(protocol, new byte[] {0});
        assertMalformed(protocol, withFullSignature(new byte[MessageSignature.BYTES - 1]));
    }

    private static void assertRoundTrip(
            ProtocolInfo<?> protocol,
            MessageSignature.Packed expectedSignature) {
        ByteBuf encoded = encodePacket(
                protocol, new ClientboundDeleteChatPacket(expectedSignature));
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));

            if (expectedSignature.id() == -1) {
                assertEquals(0, readVarInt(wire));
                byte[] actualBytes = new byte[MessageSignature.BYTES];
                wire.readBytes(actualBytes);
                assertArrayEquals(expectedSignature.fullSignature().bytes(), actualBytes);
            } else {
                assertEquals(expectedSignature.id() + 1, readVarInt(wire));
            }
            assertFalse(wire.isReadable());

            ClientboundDeleteChatPacket decoded = assertInstanceOf(
                    ClientboundDeleteChatPacket.class, decode(protocol, encoded));
            assertEquals(expectedSignature.id(), decoded.messageSignature().id());
            if (expectedSignature.fullSignature() == null) {
                assertNull(decoded.messageSignature().fullSignature());
            } else {
                assertArrayEquals(
                        expectedSignature.fullSignature().bytes(),
                        decoded.messageSignature().fullSignature().bytes());
            }
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
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

    private static byte[] withFullSignature(byte[] signature) {
        byte[] payload = new byte[signature.length + 1];
        payload[0] = 0;
        System.arraycopy(signature, 0, payload, 1, signature.length);
        return payload;
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
        return descriptor(PACKET_ID, true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            int packetId,
            boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
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

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local delete_chat must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local delete_chat must not decode");
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
        throw new AssertionError("VarInt exceeds five bytes");
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            buffer.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer.writeByte(remaining);
    }
}
