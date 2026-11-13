package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

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
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id041MountScreenOpenPacketDefinitionTest {

    private static final int PACKET_ID = 41;
    private static final String TYPE_NAME = "play.clientbound.minecraft:mount_screen_open";
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
    void classifiesMountScreenOpenAsViewerLocalMenuState() {
        var definition = new Id041MountScreenOpenPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x29, Id041MountScreenOpenPacketDefinition.PACKET_ID);
        assertEquals("minecraft:mount_screen_open", Id041MountScreenOpenPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());
    }

    @Test
    void registersExactlyOnceInUiDomainCatalogAndPaperRegistry() {
        var domainMatches = UiPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id041MountScreenOpenPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id041MountScreenOpenPacketDefinition.class, catalogMatches.getFirst());

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
    void dropsEveryServerMountMenuLayoutBeforePlaybackCodecAccess() {
        var definition = new Id041MountScreenOpenPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        ProtocolInfo<?> protocol = protocol();

        // ServerPlayer cycles its connection-local container ID through 1..100.
        // Mount layouts in this version range from zero columns to five.
        for (int containerId = 1; containerId <= 100; containerId++) {
            for (int columns = 0; columns <= 5; columns++) {
                assertDroppedBeforeCodec(
                        definition,
                        packetBody(protocol, new ClientboundMountScreenOpenPacket(
                                containerId, columns, containerId * 8 + columns)),
                        codec);
            }
        }

        for (byte[] malformedPayload : new byte[][] {
            new byte[0],
            new byte[] {(byte) 0x80}, // incomplete container ID VarInt
            new byte[] {1}, // container ID without column count
            new byte[] {1, (byte) 0x80}, // incomplete column-count VarInt
            new byte[] {1, 0}, // fields without entity ID
            new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, 0} // overlong container ID VarInt
        }) {
            assertDroppedBeforeCodec(definition, malformedPayload, codec);
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void rejectsMismatchedPlaybackDescriptorsBeforeApplyingLocalPolicy() {
        var definition = new Id041MountScreenOpenPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]),
                descriptor(PACKET_ID + 1, true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                codec,
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]),
                descriptor(PACKET_ID, false, PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                codec,
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]),
                descriptor(PACKET_ID, true, PacketDescriptor.PlaybackScope.SAFE),
                codec,
                rewriter()));
        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void nativeProtocolRoundTripsAllMountLayoutsAndSignedFieldBoundaries() {
        ProtocolInfo<?> protocol = protocol();

        for (int containerId = 1; containerId <= 100; containerId++) {
            for (int columns = 0; columns <= 5; columns++) {
                assertRoundTrip(protocol, containerId, columns, containerId * 8 + columns);
            }
        }

        int[] signedBoundaries = {
            Integer.MIN_VALUE, -1, 0, 1, 100, Integer.MAX_VALUE
        };
        for (int containerId : signedBoundaries) {
            for (int columns : signedBoundaries) {
                for (int entityId : signedBoundaries) {
                    assertRoundTrip(protocol, containerId, columns, entityId);
                }
            }
        }
    }

    @Test
    void nativeProtocolRejectsMissingAndTruncatedFieldsAndOverlongVarInts() {
        ProtocolInfo<?> protocol = protocol();

        assertMalformed(protocol, new byte[0]);
        assertMalformed(protocol, new byte[] {(byte) PACKET_ID});
        assertMalformed(protocol, new byte[] {(byte) PACKET_ID, 1});
        assertMalformed(protocol, new byte[] {(byte) PACKET_ID, 1, (byte) 0x80});
        assertMalformed(protocol, new byte[] {
            (byte) PACKET_ID, (byte) 0x80, (byte) 0x80, (byte) 0x80,
            (byte) 0x80, (byte) 0x80, 0
        });
        assertMalformed(protocol, new byte[] {(byte) PACKET_ID, 1, 0});
        assertMalformed(protocol, new byte[] {(byte) PACKET_ID, 1, 0, 0, 0, 1});
    }

    private static void assertDroppedBeforeCodec(
            Id041MountScreenOpenPacketDefinition definition,
            byte[] payload,
            PacketCodec<Object> codec) {
        PacketRewriteResult result = definition.handlePlayback(
                frame(payload), descriptor(), codec, rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    private static void assertRoundTrip(
            ProtocolInfo<?> protocol,
            int containerId,
            int columns,
            int entityId) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, new ClientboundMountScreenOpenPacket(
                    containerId, columns, entityId));
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            assertEquals(containerId, readVarInt(wire));
            assertEquals(columns, readVarInt(wire));
            assertEquals(entityId, wire.readInt());
            assertFalse(wire.isReadable());

            ClientboundMountScreenOpenPacket decoded = assertInstanceOf(
                    ClientboundMountScreenOpenPacket.class, decode(protocol, encoded));
            assertEquals(containerId, decoded.getContainerId());
            assertEquals(columns, decoded.getInventoryColumns());
            assertEquals(entityId, decoded.getEntityId());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] wireBytes) {
        ByteBuf input = Unpooled.wrappedBuffer(wireBytes);
        try {
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
        }
    }

    private static byte[] packetBody(
            ProtocolInfo<?> protocol,
            ClientboundMountScreenOpenPacket packet) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, packet);
            assertEquals(PACKET_ID, readVarInt(encoded));
            byte[] payload = new byte[encoded.readableBytes()];
            encoded.readBytes(payload);
            return payload;
        } finally {
            encoded.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
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
                PacketDisposition.STATEFUL,
                true,
                replayable,
                false,
                "paper-26.2/" + TYPE_NAME,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local mount_screen_open must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local mount_screen_open must not decode");
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf output, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(output, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf input) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(input);
    }

    private static int readVarInt(ByteBuf buffer) {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            int next = buffer.readUnsignedByte();
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new AssertionError("VarInt exceeds five bytes");
    }
}
