package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.world;

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
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
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
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id010ChangeDifficultyPacketDefinitionTest {

    private static final String TYPE_NAME =
            "play.clientbound.minecraft:change_difficulty";
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
    void classifiesDifficultyAsViewerLocalConnectionState() {
        var definition = new Id010ChangeDifficultyPacketDefinition();

        assertEquals(10, definition.packetId());
        assertEquals(0x0A, Id010ChangeDifficultyPacketDefinition.PACKET_ID);
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
    void dropsEveryDefinedDifficultyAndLockCombinationBeforeDecode() {
        var definition = new Id010ChangeDifficultyPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (Difficulty difficulty : Difficulty.values()) {
            for (boolean locked : List.of(false, true)) {
                PacketRewriteResult result = definition.handlePlayback(
                        frame(new byte[] {
                            (byte) difficulty.getId(),
                            (byte) (locked ? 1 : 0)
                        }),
                        descriptor(),
                        codec,
                        rewriter());

                assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
                assertTrue(result.packet().isEmpty());
                assertEquals(
                        "recorded connection state does not belong to the observer",
                        result.reason());
            }
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void dropsMalformedPayloadsBeforeDecode() {
        var definition = new Id010ChangeDifficultyPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : List.of(
                new byte[0],
                new byte[] {0},
                new byte[] {(byte) 0x80},
                new byte[] {
                    (byte) 0x80,
                    (byte) 0x80,
                    (byte) 0x80,
                    (byte) 0x80,
                    (byte) 0x80,
                    (byte) 0x80
                },
                new byte[] {3, 1, 99})) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());

            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingDecodedType() {
        PacketRewriteResult result = new Id010ChangeDifficultyPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingLocalPolicy() {
        var definition = new Id010ChangeDifficultyPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", 10, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 11, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 10, PacketPhase.CONFIGURATION,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 10, PacketPhase.PLAY,
                        PacketDescriptor.Direction.SERVERBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 10, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, 10, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            frame(new byte[] {2, 0}), mismatch, codec, rewriter()));
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void enforcesNullBoundariesBeforeApplyingLocalPolicy() {
        var definition = new Id010ChangeDifficultyPacketDefinition();
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
    void nativeProtocolRoundTripsEveryDifficultyAndLockCombination() {
        ProtocolInfo<?> protocol = protocol();

        for (Difficulty difficulty : Difficulty.values()) {
            for (boolean locked : List.of(false, true)) {
                ClientboundChangeDifficultyPacket original =
                        new ClientboundChangeDifficultyPacket(difficulty, locked);
                ByteBuf encoded = Unpooled.buffer();
                try {
                    encode(protocol, encoded, original);

                    ByteBuf wire = encoded.duplicate();
                    assertEquals(0x0A, readVarInt(wire));
                    assertEquals(difficulty.getId(), readVarInt(wire));
                    assertEquals(locked ? 1 : 0, wire.readUnsignedByte());
                    assertFalse(wire.isReadable());

                    ClientboundChangeDifficultyPacket decoded = assertInstanceOf(
                            ClientboundChangeDifficultyPacket.class,
                            decode(protocol, encoded));
                    assertFalse(encoded.isReadable());
                    assertEquals(difficulty, decoded.difficulty());
                    assertEquals(locked, decoded.locked());
                } finally {
                    encoded.release();
                }
            }
        }
    }

    @Test
    void nativeProtocolCanonicalizesOutOfRangeIdsAndBooleanBytes() {
        ProtocolInfo<?> protocol = protocol();

        assertNativeCanonicalization(protocol, 4, 0x00);
        assertNativeCanonicalization(protocol, -1, 0x02);
        assertNativeCanonicalization(protocol, Integer.MIN_VALUE, 0x7F);
        assertNativeCanonicalization(protocol, Integer.MAX_VALUE, 0xFF);
    }

    @Test
    void nativeProtocolRejectsIncompleteAndOverlongBodies() {
        ProtocolInfo<?> protocol = protocol();

        assertNativeDecodeFails(protocol, new byte[0]);
        assertNativeDecodeFails(protocol, new byte[] {0});
        assertNativeDecodeFails(protocol, new byte[] {(byte) 0x80});
        assertNativeDecodeFails(protocol, new byte[] {
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            0
        });
    }

    private static void assertNativeCanonicalization(
            ProtocolInfo<?> protocol,
            int rawDifficultyId,
            int rawBoolean) {
        ByteBuf raw = Unpooled.buffer();
        try {
            VarInt.write(raw, 0x0A);
            VarInt.write(raw, rawDifficultyId);
            raw.writeByte(rawBoolean);

            ClientboundChangeDifficultyPacket decoded = assertInstanceOf(
                    ClientboundChangeDifficultyPacket.class,
                    decode(protocol, raw));
            assertFalse(raw.isReadable());
            assertEquals(difficultyForRawId(rawDifficultyId), decoded.difficulty());
            assertEquals(rawBoolean != 0, decoded.locked());

            ByteBuf canonical = Unpooled.buffer();
            try {
                encode(protocol, canonical, decoded);
                assertEquals(0x0A, readVarInt(canonical));
                assertEquals(decoded.difficulty().getId(), readVarInt(canonical));
                assertEquals(decoded.locked() ? 1 : 0, canonical.readUnsignedByte());
                assertFalse(canonical.isReadable());
            } finally {
                canonical.release();
            }
        } finally {
            raw.release();
        }
    }

    private static void assertNativeDecodeFails(ProtocolInfo<?> protocol, byte[] body) {
        ByteBuf raw = Unpooled.buffer();
        try {
            VarInt.write(raw, 0x0A);
            raw.writeBytes(body);
            assertThrows(RuntimeException.class, () -> decode(protocol, raw));
        } finally {
            raw.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
    }

    private static Paper26_3ReplayPacketRewriter rewriter() {
        return new Paper26_3ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(
                TYPE_NAME,
                10,
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
                "paper-26.3/" + typeName,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 10, payload);
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local change_difficulty must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local change_difficulty must not decode");
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
        return VarInt.read(buffer);
    }

    private static Difficulty difficultyForRawId(int rawDifficultyId) {
        Difficulty[] values = Difficulty.values();
        return values[Math.floorMod(rawDifficultyId, values.length)];
    }
}
