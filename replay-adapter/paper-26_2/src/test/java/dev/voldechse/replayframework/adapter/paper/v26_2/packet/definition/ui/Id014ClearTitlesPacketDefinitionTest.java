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
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id014ClearTitlesPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:clear_titles";
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
    void classifiesTitleClearingAsViewerLocalUiState() {
        var definition = new Id014ClearTitlesPacketDefinition();

        assertEquals(14, definition.packetId());
        assertEquals(0x0E, Id014ClearTitlesPacketDefinition.PACKET_ID);
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
    void dropsEveryPossibleBooleanWireValueBeforeDecodeOrEncode() {
        var definition = new Id014ClearTitlesPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (int rawBoolean = 0; rawBoolean <= 0xFF; rawBoolean++) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(new byte[] {(byte) rawBoolean}), descriptor(), codec, rewriter());

            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
            assertEquals(
                    "recorded connection state does not belong to the observer",
                    result.reason());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void dropsMalformedAndOverlongPayloadsBeforeDecodeOrEncode() {
        var definition = new Id014ClearTitlesPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : List.of(
                new byte[0],
                new byte[] {0, 1},
                new byte[] {(byte) 0xFF, 0, 1, 2})) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());

            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingDecodedType() {
        PacketRewriteResult result = new Id014ClearTitlesPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingLocalPolicy() {
        var definition = new Id014ClearTitlesPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", 14, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 15, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 14, PacketPhase.CONFIGURATION,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 14, PacketPhase.PLAY,
                        PacketDescriptor.Direction.SERVERBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 14, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 14, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            frame(new byte[] {0}), mismatch, codec, rewriter()));
        }
    }

    @Test
    void enforcesNullBoundariesBeforeApplyingLocalPolicy() {
        var definition = new Id014ClearTitlesPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), null, codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), descriptor(), null, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), descriptor(), codec, null));
        assertThrows(NullPointerException.class, () ->
                definition.rewrite(null, descriptor(), rewriter()));
    }

    @Test
    void nativeProtocolRoundTripsClearAndResetOperations() {
        ProtocolInfo<?> protocol = protocol();

        assertNativeRoundTrip(protocol, false, 0);
        assertNativeRoundTrip(protocol, true, 1);
    }

    @Test
    void nativeProtocolCanonicalizesEveryNonZeroBooleanByte() {
        ProtocolInfo<?> protocol = protocol();

        for (int rawBoolean = 1; rawBoolean <= 0xFF; rawBoolean++) {
            ByteBuf raw = Unpooled.buffer();
            try {
                VarInt.write(raw, 0x0E);
                raw.writeByte(rawBoolean);

                ClientboundClearTitlesPacket decoded = assertInstanceOf(
                        ClientboundClearTitlesPacket.class, decode(protocol, raw));
                assertFalse(raw.isReadable());
                assertTrue(decoded.shouldResetTimes());

                ByteBuf canonical = Unpooled.buffer();
                try {
                    encode(protocol, canonical, decoded);
                    assertEquals(0x0E, VarInt.read(canonical));
                    assertEquals(1, canonical.readUnsignedByte());
                    assertFalse(canonical.isReadable());
                } finally {
                    canonical.release();
                }
            } finally {
                raw.release();
            }
        }
    }

    @Test
    void nativeProtocolRejectsMissingBooleanAndLeavesTrailingBytesForFraming() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf missing = Unpooled.buffer();
        try {
            VarInt.write(missing, 0x0E);
            assertThrows(RuntimeException.class, () -> decode(protocol, missing));
        } finally {
            missing.release();
        }

        ByteBuf trailing = Unpooled.buffer();
        try {
            VarInt.write(trailing, 0x0E);
            trailing.writeByte(0);
            trailing.writeByte(0x7A);

            ClientboundClearTitlesPacket decoded = assertInstanceOf(
                    ClientboundClearTitlesPacket.class, decode(protocol, trailing));
            assertFalse(decoded.shouldResetTimes());
            assertEquals(0x7A, trailing.readUnsignedByte());
            assertFalse(trailing.isReadable());
        } finally {
            trailing.release();
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            boolean resetTimes,
            int expectedWireValue) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, new ClientboundClearTitlesPacket(resetTimes));
            ByteBuf wireView = encoded.duplicate();
            assertEquals(0x0E, VarInt.read(wireView));
            assertEquals(expectedWireValue, wireView.readUnsignedByte());
            assertFalse(wireView.isReadable());

            ClientboundClearTitlesPacket decoded = assertInstanceOf(
                    ClientboundClearTitlesPacket.class, decode(protocol, encoded));
            assertFalse(encoded.isReadable());
            assertEquals(resetTimes, decoded.shouldResetTimes());
        } finally {
            encoded.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(
                TYPE_NAME,
                14,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                true,
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            String typeName,
            int packetId,
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        boolean clientbound = direction == PacketDescriptor.Direction.CLIENTBOUND;
        return new PacketDescriptor(
                typeName,
                phase,
                direction,
                packetId,
                PacketDisposition.STATEFUL,
                clientbound,
                replayable,
                false,
                "paper-26.2/" + typeName,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 14, payload);
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local clear_titles must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local clear_titles must not decode");
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
