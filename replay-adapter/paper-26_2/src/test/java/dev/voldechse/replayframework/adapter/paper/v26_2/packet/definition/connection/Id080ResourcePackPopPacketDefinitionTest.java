package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id080ResourcePackPopPacketDefinitionTest {

    private static final int PACKET_ID = 80;
    private static final String TYPE_NAME = "play.clientbound.minecraft:resource_pack_pop";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID[] PACK_IDS = {
        new UUID(0L, 0L),
        new UUID(1L, 2L),
        UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
        new UUID(Long.MIN_VALUE, Long.MAX_VALUE),
        new UUID(-1L, -1L)
    };

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesResourcePackRemovalAsCapturedButUnsafeConnectionControl() {
        var definition = new Id080ResourcePackPopPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x50, Id080ResourcePackPopPacketDefinition.PACKET_ID);
        assertEquals("minecraft:resource_pack_pop", Id080ResourcePackPopPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.CONTROL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertFalse(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.UNSAFE,
                definition.classification().playbackScope());
    }

    @Test
    void registersExactlyOnceInConnectionDomainCatalogAndPaperRegistry() {
        var domainMatches = ConnectionPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id080ResourcePackPopPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id080ResourcePackPopPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.CONTROL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertFalse(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.UNSAFE, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void blocksPayloadsBeforePlaybackCodecAccess() {
        var definition = new Id080ResourcePackPopPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[] {1},
            new byte[] {1, 0},
            new byte[] {(byte) 0xFF}
        }) {
            IncompatibleAdapterException failure = assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(frame(payload), descriptor(), codec, rewriter()));
            assertEquals(
                    "packet descriptor is not permitted for playback: " + TYPE_NAME,
                    failure.getMessage());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void rejectsAlreadyDecodedValuesWithoutInvokingPacketRewrite() {
        IncompatibleAdapterException failure = assertThrows(
                IncompatibleAdapterException.class,
                () -> new Id080ResourcePackPopPacketDefinition()
                        .rewrite(new Object(), descriptor(), rewriter()));

        assertEquals(
                "packet descriptor is not permitted for playback: " + TYPE_NAME,
                failure.getMessage());
    }

    @Test
    void validatesPlaybackDescriptorBeforeAnyCodecAccess() {
        var definition = new Id080ResourcePackPopPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, new AtomicInteger());

        assertThrows(NullPointerException.class, () -> definition.handlePlayback(
                null, descriptor(), codec, rewriter()));
        assertThrows(NullPointerException.class, () -> definition.handlePlayback(
                frame(new byte[0]), null, codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(), null, rewriter()));

        PacketDescriptor mismatchedId = new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                PACKET_ID + 1,
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.UNSAFE);
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), mismatchedId, codec, rewriter()));

        assertEquals(0, decodes.get());
    }

    @Test
    void nativePlayProtocolUsesIdEightyAndEncodesTheOptionalUuidPrefix() {
        ProtocolInfo<?> protocol = clientboundProtocol();

        assertNativeRoundTrip(protocol, Optional.empty());
        for (UUID id : PACK_IDS) {
            assertNativeRoundTrip(protocol, Optional.of(id));
        }
    }

    @Test
    void absentUuidAndPresentUuidExpressDistinctRemovalScopes() {
        ProtocolInfo<?> protocol = clientboundProtocol();
        UUID id = UUID.fromString("12345678-9abc-def0-1234-56789abcdef0");

        ClientboundResourcePackPopPacket allPacks = decodePacket(protocol, new byte[] {0x50, 0});
        assertEquals(Optional.empty(), allPacks.id());

        ByteBuf specificPacket = Unpooled.buffer();
        try {
            writePacketIdAndOptionalUuid(specificPacket, Optional.of(id));
            ClientboundResourcePackPopPacket decoded = assertInstanceOf(
                    ClientboundResourcePackPopPacket.class, decode(protocol, specificPacket));
            assertEquals(Optional.of(id), decoded.id());
            assertFalse(specificPacket.isReadable());
        } finally {
            specificPacket.release();
        }
    }

    @Test
    void nativeCodecUsesBooleanThenUuidAndPreservesFollowingPacketBytes() {
        ProtocolInfo<?> protocol = clientboundProtocol();
        UUID id = PACK_IDS[2];
        ByteBuf input = Unpooled.buffer();
        try {
            writePacketIdAndOptionalUuid(input, Optional.empty());
            writePacketIdAndOptionalUuid(input, Optional.of(id));
            input.writeByte(0x5A);

            ClientboundResourcePackPopPacket allPacks = assertInstanceOf(
                    ClientboundResourcePackPopPacket.class, decode(protocol, input));
            ClientboundResourcePackPopPacket onePack = assertInstanceOf(
                    ClientboundResourcePackPopPacket.class, decode(protocol, input));

            assertEquals(Optional.empty(), allPacks.id());
            assertEquals(Optional.of(id), onePack.id());
            assertEquals(0x5A, input.readUnsignedByte());
            assertFalse(input.isReadable());
        } finally {
            input.release();
        }
    }

    @Test
    void nativeCodecTreatsAnyNonzeroOptionalFlagAsPresent() {
        ProtocolInfo<?> protocol = clientboundProtocol();
        UUID id = PACK_IDS[1];

        for (int optionalFlag : new int[] {1, 2, 0x7F, 0x80, 0xFF}) {
            ByteBuf input = Unpooled.buffer();
            try {
                input.writeByte(PACKET_ID);
                input.writeByte(optionalFlag);
                input.writeLong(id.getMostSignificantBits());
                input.writeLong(id.getLeastSignificantBits());

                ClientboundResourcePackPopPacket decoded = assertInstanceOf(
                        ClientboundResourcePackPopPacket.class, decode(protocol, input));
                assertEquals(Optional.of(id), decoded.id());
                assertFalse(input.isReadable());
            } finally {
                input.release();
            }
        }
    }

    @Test
    void nativeCodecRejectsMissingOptionalFlagAndEveryTruncatedUuid() {
        ProtocolInfo<?> protocol = clientboundProtocol();
        assertMalformed(protocol, new byte[0]);
        assertMalformed(protocol, new byte[] {1});
        assertMalformed(protocol, new byte[] {(byte) 0x80});

        for (int uuidLength = 0; uuidLength < Long.BYTES * 2; uuidLength++) {
            byte[] payload = new byte[1 + uuidLength];
            payload[0] = 1;
            assertMalformed(protocol, payload);
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, Optional<UUID> expectedId) {
        ByteBuf encoded = encodePacket(protocol, new ClientboundResourcePackPopPacket(expectedId));
        try {
            ByteBuf wireView = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wireView));
            assertEquals(expectedId.isPresent(), wireView.readBoolean());
            if (expectedId.isPresent()) {
                UUID encodedId = new UUID(wireView.readLong(), wireView.readLong());
                assertEquals(expectedId.orElseThrow(), encodedId);
            }
            assertFalse(wireView.isReadable());

            ClientboundResourcePackPopPacket decoded = assertInstanceOf(
                    ClientboundResourcePackPopPacket.class, decode(protocol, encoded));
            assertEquals(expectedId, decoded.id());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf input = Unpooled.buffer();
        try {
            input.writeByte(PACKET_ID);
            input.writeBytes(payload);
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
        }
    }

    private static ClientboundResourcePackPopPacket decodePacket(
            ProtocolInfo<?> protocol, byte[] packetBytes) {
        ByteBuf input = Unpooled.wrappedBuffer(packetBytes);
        try {
            return assertInstanceOf(ClientboundResourcePackPopPacket.class, decode(protocol, input));
        } finally {
            input.release();
        }
    }

    private static void writePacketIdAndOptionalUuid(ByteBuf output, Optional<UUID> id) {
        VarInt.write(output, PACKET_ID);
        output.writeBoolean(id.isPresent());
        id.ifPresent(value -> {
            output.writeLong(value.getMostSignificantBits());
            output.writeLong(value.getLeastSignificantBits());
        });
    }

    private static ByteBuf encodePacket(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf output = Unpooled.buffer();
        try {
            encode(protocol, output, packet);
            return output;
        } catch (RuntimeException | Error failure) {
            output.release();
            throw failure;
        }
    }

    private static ProtocolInfo<?> clientboundProtocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID), Set.of(OBSERVER_ID), Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                PACKET_ID,
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.UNSAFE);
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
                throw new AssertionError("resource_pack_pop must not encode during playback");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("resource_pack_pop must not decode during playback");
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
