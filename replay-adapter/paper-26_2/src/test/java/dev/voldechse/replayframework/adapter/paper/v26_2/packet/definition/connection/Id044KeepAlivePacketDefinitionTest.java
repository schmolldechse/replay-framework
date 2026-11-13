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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id044KeepAlivePacketDefinitionTest {

    private static final int PACKET_ID = 44;
    private static final String TYPE_NAME = "play.clientbound.minecraft:keep_alive";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long[] CHALLENGE_IDS = {
        Long.MIN_VALUE,
        Long.MIN_VALUE + 1,
        -1L,
        0L,
        1L,
        0x0123456789ABCDEFL,
        Long.MAX_VALUE - 1,
        Long.MAX_VALUE
    };

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesKeepAliveAsCapturedButNonReplayableConnectionControl() {
        var definition = new Id044KeepAlivePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x2C, Id044KeepAlivePacketDefinition.PACKET_ID);
        assertEquals("minecraft:keep_alive", Id044KeepAlivePacketDefinition.IDENTIFIER);
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
        assertInstanceOf(Id044KeepAlivePacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id044KeepAlivePacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.CONTROL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertFalse(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.UNSAFE, descriptor.playbackScope());
    }

    @Test
    void blocksEveryChallengePayloadBeforePlaybackDecodeOrEncode() {
        var definition = new Id044KeepAlivePacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[Long.BYTES],
            new byte[] {
                (byte) 0x80, (byte) 0x81, (byte) 0x82, (byte) 0x83,
                (byte) 0x84, (byte) 0x85, (byte) 0x86, (byte) 0x87,
                (byte) 0x88
            }
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
    void blocksAlreadyDecodedChallengesWithoutInspectingTheirType() {
        var definition = new Id044KeepAlivePacketDefinition();
        IncompatibleAdapterException failure = assertThrows(
                IncompatibleAdapterException.class,
                () -> definition.rewrite(new Object(), descriptor(), rewriter()));

        assertEquals(
                "packet descriptor is not permitted for playback: " + TYPE_NAME,
                failure.getMessage());
    }

    @Test
    void nativeProtocolUsesIdFortyFourAndRoundTripsSignedLongBoundaries() {
        ProtocolInfo<?> protocol = protocol();

        for (long challengeId : CHALLENGE_IDS) {
            assertNativeRoundTrip(protocol, challengeId);
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedFixedWidthChallenge() {
        ProtocolInfo<?> protocol = protocol();

        for (int payloadLength = 0; payloadLength < Long.BYTES; payloadLength++) {
            ByteBuf truncated = Unpooled.buffer();
            try {
                writeVarInt(truncated, PACKET_ID);
                truncated.writeZero(payloadLength);
                assertThrows(RuntimeException.class, () -> decode(protocol, truncated));
            } finally {
                truncated.release();
            }
        }
    }

    private static void assertNativeRoundTrip(ProtocolInfo<?> protocol, long expectedId) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, new ClientboundKeepAlivePacket(expectedId));

            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            assertEquals(Long.BYTES, wire.readableBytes());
            assertEquals(expectedId, wire.readLong());
            assertFalse(wire.isReadable());

            ClientboundKeepAlivePacket decoded = assertInstanceOf(
                    ClientboundKeepAlivePacket.class, decode(protocol, encoded));
            assertEquals(expectedId, decoded.getId());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
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
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("blocked keep_alive must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("blocked keep_alive must not decode");
            }
        };
    }

    private static int readVarInt(ByteBuf buffer) {
        int result = 0;
        int position = 0;
        while (true) {
            if (position >= 35) {
                throw new IllegalArgumentException("VarInt is too big");
            }
            byte current = buffer.readByte();
            result |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return result;
            }
            position += 7;
        }
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            buffer.writeByte(value & 0x7F | 0x80);
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
}
