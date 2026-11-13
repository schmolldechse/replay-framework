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
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id062PongResponsePacketDefinitionTest {

    private static final int PACKET_ID = 62;
    private static final int PING_REQUEST_PACKET_ID = 38;
    private static final String TYPE_NAME = "play.clientbound.minecraft:pong_response";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long[] PING_TIMES = {
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
    void classifiesPongResponseAsCapturedButNonReplayableConnectionControl() {
        var definition = new Id062PongResponsePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x3E, Id062PongResponsePacketDefinition.PACKET_ID);
        assertEquals("minecraft:pong_response", Id062PongResponsePacketDefinition.IDENTIFIER);
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
        assertInstanceOf(Id062PongResponsePacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id062PongResponsePacketDefinition.class, catalogMatches.getFirst());

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
    void blocksValidAndMalformedTimestampPayloadsBeforePlaybackDecodeOrEncode() {
        var definition = new Id062PongResponsePacketDefinition();
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
    void blocksAlreadyDecodedResponsesWithoutInspectingTheirType() {
        IncompatibleAdapterException failure = assertThrows(
                IncompatibleAdapterException.class,
                () -> new Id062PongResponsePacketDefinition().rewrite(
                        new Object(), descriptor(), rewriter()));

        assertEquals(
                "packet descriptor is not permitted for playback: " + TYPE_NAME,
                failure.getMessage());
    }

    @Test
    void nativeProtocolsRoundTripEverySignedPingTimeAndKeepTheCorrelationValue() {
        ProtocolInfo<?> clientbound = clientboundProtocol();
        ProtocolInfo<?> serverbound = serverboundProtocol();

        for (long pingTime : PING_TIMES) {
            ByteBuf encodedRequest = encodePacket(
                    serverbound, new ServerboundPingRequestPacket(pingTime));
            try {
                ByteBuf requestWire = encodedRequest.duplicate();
                assertEquals(PING_REQUEST_PACKET_ID, VarInt.read(requestWire));
                assertEquals(Long.BYTES, requestWire.readableBytes());
                assertEquals(pingTime, requestWire.readLong());
                assertFalse(requestWire.isReadable());

                ServerboundPingRequestPacket decodedRequest = assertInstanceOf(
                        ServerboundPingRequestPacket.class, decode(serverbound, encodedRequest));
                assertEquals(pingTime, decodedRequest.getTime());
                assertFalse(encodedRequest.isReadable());

                ByteBuf encodedResponse = encodePacket(
                        clientbound, new ClientboundPongResponsePacket(decodedRequest.getTime()));
                try {
                    ByteBuf responseWire = encodedResponse.duplicate();
                    assertEquals(PACKET_ID, VarInt.read(responseWire));
                    assertEquals(pingTime, responseWire.readLong());
                    assertFalse(responseWire.isReadable());

                    ClientboundPongResponsePacket decodedResponse = assertInstanceOf(
                            ClientboundPongResponsePacket.class,
                            decode(clientbound, encodedResponse));
                    assertEquals(decodedRequest.getTime(), decodedResponse.time());
                    assertFalse(encodedResponse.isReadable());
                } finally {
                    encodedResponse.release();
                }
            } finally {
                encodedRequest.release();
            }
        }
    }

    @Test
    void nativeClientboundProtocolRejectsEveryTruncatedFixedWidthTimestamp() {
        ProtocolInfo<?> protocol = clientboundProtocol();

        for (int payloadLength = 0; payloadLength < Long.BYTES; payloadLength++) {
            ByteBuf truncated = Unpooled.buffer();
            try {
                VarInt.write(truncated, PACKET_ID);
                truncated.writeZero(payloadLength);
                assertThrows(RuntimeException.class, () -> decode(protocol, truncated));
            } finally {
                truncated.release();
            }
        }
    }

    @Test
    void nativeClientboundProtocolLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = clientboundProtocol();
        ByteBuf encoded = encodePacket(
                protocol, new ClientboundPongResponsePacket(0x123456789ABCDEFL));
        try {
            encoded.writeByte(0x7A);
            ClientboundPongResponsePacket decoded = assertInstanceOf(
                    ClientboundPongResponsePacket.class, decode(protocol, encoded));
            assertEquals(0x123456789ABCDEFL, decoded.time());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static ProtocolInfo<?> clientboundProtocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static ProtocolInfo<?> serverboundProtocol() {
        return GameProtocols.SERVERBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY),
                new GameProtocols.Context() {
                    @Override
                    public boolean hasInfiniteMaterials() {
                        return false;
                    }

                    @Override
                    public boolean canUseCommandBlocks() {
                        return false;
                    }
                });
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
            AtomicInteger decodes, AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("blocked pong response must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("blocked pong response must not decode");
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }
}
