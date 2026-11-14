package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

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
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2CheckpointEncoder;
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
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id091SetBorderWarningDelayPacketDefinitionTest {

    private static final int PACKET_ID = 91;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_border_warning_delay";
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
    void classifiesWarningDelayAsSafeCheckpointRelevantWorldState() {
        var definition = new Id091SetBorderWarningDelayPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x5B, Id091SetBorderWarningDelayPacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_border_warning_delay",
                Id091SetBorderWarningDelayPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE,
                definition.classification().playbackScope());
        assertEquals(Paper26_2CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));
    }

    @Test
    void registersExactlyOnceInWorldDomainCatalogAndPaperRegistry() {
        var domainMatches = WorldPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id091SetBorderWarningDelayPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id091SetBorderWarningDelayPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void nativeProtocolPreservesAllVarIntWidthBoundaries() {
        ProtocolInfo<?> protocol = protocol();
        for (int warningDelay : new int[] {
            0, 1, 127, 128, 16_383, 16_384, Integer.MAX_VALUE
        }) {
            assertNativeRoundTrip(protocol, packet(warningDelay));
        }
    }

    @Test
    void nativeProtocolAcceptsOverlongVarIntAndCanonicalizesIt() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf input = Unpooled.buffer();
        try {
            VarInt.write(input, PACKET_ID);
            input.writeByte(0x80);
            input.writeByte(0x00);

            ClientboundSetBorderWarningDelayPacket decoded = assertInstanceOf(
                    ClientboundSetBorderWarningDelayPacket.class, decode(protocol, input));
            assertEquals(0, decoded.getWarningDelay());
            assertFalse(input.isReadable());

            ByteBuf canonical = encodePacket(protocol, decoded);
            try {
                FriendlyByteBuf wire = new FriendlyByteBuf(canonical.duplicate());
                assertEquals(PACKET_ID, VarInt.read(wire));
                assertEquals(0, wire.readVarInt());
                assertEquals(1, canonical.readableBytes() - 1);
                assertFalse(wire.isReadable());
            } finally {
                canonical.release();
            }
        } finally {
            input.release();
        }
    }

    @Test
    void playbackPreservesTheWarningDelayAndNativePayload() {
        var definition = new Id091SetBorderWarningDelayPacketDefinition();

        for (int warningDelay : new int[] {0, 128, Integer.MAX_VALUE}) {
            ClientboundSetBorderWarningDelayPacket original = packet(warningDelay);
            PacketRewriteResult result = definition.handlePlayback(
                    frame(new byte[0]), descriptor(), codec(original), rewriter());
            ClientboundSetBorderWarningDelayPacket rebuilt = assertInstanceOf(
                    ClientboundSetBorderWarningDelayPacket.class, result.packet().orElseThrow());

            assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
            assertEquals(warningDelay, rebuilt.getWarningDelay());
            var encoded = definition.encode(rebuilt, descriptor(), codec(rebuilt));
            assertEquals(PACKET_ID, encoded.packetId());
            assertArrayEquals(nativePayload(rebuilt), encoded.payload());
        }
    }

    @Test
    void rejectsNegativeWarningDelaysFromEitherValueOrNativePacket() {
        var definition = new Id091SetBorderWarningDelayPacketDefinition();
        assertThrows(IncompatibleAdapterException.class,
                () -> new Id091SetBorderWarningDelayPacketDefinition.Value(-1));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.read(packet(-1)));
        assertEquals(0, definition.read(packet(0)).warningDelay());
    }

    @Test
    void rejectsDescriptorMismatchesAndNonReplayableDescriptors() {
        var definition = new Id091SetBorderWarningDelayPacketDefinition();
        ClientboundSetBorderWarningDelayPacket original = packet(20);

        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame(new byte[0]),
                        descriptor(TYPE_NAME, PACKET_ID + 1, true), codec(original), rewriter()));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame(new byte[0]),
                        descriptor("play.clientbound.minecraft:other", PACKET_ID, true),
                        codec(original), rewriter()));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame(new byte[0]),
                        descriptor(TYPE_NAME, PACKET_ID, false), codec(original), rewriter()));
    }

    @Test
    void nativeProtocolRejectsTruncatedAndOverlongVarInts() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol, packet(Integer.MAX_VALUE));
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            byte[] body = bytes(wire);
            assertEquals(5, body.length);
            for (int length = 0; length < body.length; length++) {
                int prefixLength = length;
                assertNativeDecodeFails(protocol,
                        buffer -> buffer.writeBytes(body, 0, prefixLength));
            }
        } finally {
            encoded.release();
        }

        assertNativeDecodeFails(protocol, buffer -> {
            for (int index = 0; index < 6; index++) {
                buffer.writeByte(0x80);
            }
        });
    }

    @Test
    void nativeProtocolLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol, packet(200));
        try {
            encoded.writeByte(0x7A);
            ClientboundSetBorderWarningDelayPacket decoded = assertInstanceOf(
                    ClientboundSetBorderWarningDelayPacket.class, decode(protocol, encoded));
            assertEquals(200, decoded.getWarningDelay());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundSetBorderWarningDelayPacket original) {
        ByteBuf encoded = encodePacket(protocol, original);
        try {
            byte[] originalWire = bytes(encoded);
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(original.getWarningDelay(), wire.readVarInt());
            assertFalse(wire.isReadable());

            ClientboundSetBorderWarningDelayPacket decoded = assertInstanceOf(
                    ClientboundSetBorderWarningDelayPacket.class, decode(protocol, encoded));
            assertEquals(original.getWarningDelay(), decoded.getWarningDelay());
            var definition = new Id091SetBorderWarningDelayPacketDefinition();
            assertEquals(definition.read(original), definition.read(decoded));
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encodePacket(protocol, definition.rebuild(definition.read(decoded)));
            try {
                assertArrayEquals(originalWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static ClientboundSetBorderWarningDelayPacket packet(int warningDelay) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(warningDelay);
            return ClientboundSetBorderWarningDelayPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static byte[] nativePayload(ClientboundSetBorderWarningDelayPacket packet) {
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
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
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
                PacketDescriptor.PlaybackScope.SAFE);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> codec(ClientboundSetBorderWarningDelayPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                ClientboundSetBorderWarningDelayPacket nativePacket = assertInstanceOf(
                        ClientboundSetBorderWarningDelayPacket.class, packet);
                ByteBuf encoded = encodePacket(protocol(), nativePacket);
                try {
                    assertEquals(PACKET_ID, VarInt.read(encoded));
                    return new EncodedPacket(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            PACKET_ID,
                            bytes(encoded));
                } finally {
                    encoded.release();
                }
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                return decoded;
            }
        };
    }

    private static void assertNativeDecodeFails(
            ProtocolInfo<?> protocol,
            java.util.function.Consumer<ByteBuf> bodyWriter) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            VarInt.write(encoded, PACKET_ID);
            bodyWriter.accept(encoded);
            assertThrows(RuntimeException.class, () -> decode(protocol, encoded));
        } finally {
            encoded.release();
        }
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
