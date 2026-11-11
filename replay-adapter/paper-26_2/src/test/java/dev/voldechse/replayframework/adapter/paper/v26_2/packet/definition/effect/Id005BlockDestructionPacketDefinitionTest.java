package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id005BlockDestructionPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:block_destruction";
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesBlockCracksAsEphemeralIdentityBearingEffect() {
        var definition = new Id005BlockDestructionPacketDefinition();

        assertEquals(5, definition.packetId());
        assertEquals(0x05, Id005BlockDestructionPacketDefinition.PACKET_ID);
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
    void preservesEveryWireProgressWhileRewritingTheOverlayIdentity() {
        var definition = new Id005BlockDestructionPacketDefinition();
        var context = rewriter();
        BlockPos position = new BlockPos(-31, 72, 44);
        int mappedId = context.mapEntityId(41);

        for (int progress = 0; progress <= 0xFF; progress++) {
            Id005BlockDestructionPacketDefinition.Value value =
                    definition.read(new ClientboundBlockDestructionPacket(41, position, progress));
            Id005BlockDestructionPacketDefinition.Value rewritten = definition
                    .rewrite(value, context)
                    .value()
                    .orElseThrow();
            ClientboundBlockDestructionPacket rebuilt = definition.rebuild(rewritten);

            assertEquals(mappedId, rebuilt.getId(), "progress " + progress);
            assertSame(position, rebuilt.getPos(), "progress " + progress);
            assertEquals(progress, rebuilt.getProgress(), "progress " + progress);
        }

        assertNotEquals(41, mappedId);
        assertEquals(0xFF, definition.read(
                new ClientboundBlockDestructionPacket(1, position, -1)).progress());
        assertEquals(0, definition.read(
                new ClientboundBlockDestructionPacket(1, position, 0x100)).progress());
    }

    @Test
    void validatesTheCanonicalValueModelAndDecodedType() {
        var definition = new Id005BlockDestructionPacketDefinition();

        assertThrows(NullPointerException.class, () ->
                new Id005BlockDestructionPacketDefinition.Value(1, null, 0));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id005BlockDestructionPacketDefinition.Value(1, BlockPos.ZERO, -1));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id005BlockDestructionPacketDefinition.Value(1, BlockPos.ZERO, 0x100));
        assertThrows(IncompatibleAdapterException.class, () ->
                definition.rewrite(new Object(), descriptor(), rewriter()));
    }

    @Test
    void handlesPlaybackAndValidatedEncode() {
        var definition = new Id005BlockDestructionPacketDefinition();
        var context = rewriter();
        BlockPos position = new BlockPos(10, -64, -20);
        ClientboundBlockDestructionPacket original =
                new ClientboundBlockDestructionPacket(51, position, 255);

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);
        ClientboundBlockDestructionPacket rewritten = assertInstanceOf(
                ClientboundBlockDestructionPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(context.mapEntityId(51), rewritten.getId());
        assertEquals(position, rewritten.getPos());
        assertEquals(255, rewritten.getProgress());
        assertEquals(5, definition.encode(rewritten, descriptor(), codec(original)).packetId());
    }

    @Test
    void nativeProtocolUsesIdFiveAndCoversAllWireValues() {
        ProtocolInfo<?> protocol = protocol();
        BlockPos origin = new BlockPos(0, 64, 0);

        for (int progress = 0; progress <= 0xFF; progress++) {
            assertNativeRoundTrip(protocol, 7, origin, progress);
        }
        for (int id : new int[] {
            Integer.MIN_VALUE, -1, 0, 1, 127, 128, 16_383, 16_384, Integer.MAX_VALUE
        }) {
            assertNativeRoundTrip(protocol, id, origin, 9);
        }
        for (BlockPos position : new BlockPos[] {
            new BlockPos(-33_554_432, -2_048, -33_554_432),
            BlockPos.ZERO,
            new BlockPos(33_554_431, 2_047, 33_554_431)
        }) {
            assertNativeRoundTrip(protocol, 8, position, 255);
        }
    }

    @Test
    void nativeProtocolRejectsIncompleteAndOversizedBodies() {
        ProtocolInfo<?> protocol = protocol();

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80},
            new byte[] {0},
            new byte[] {0, 0, 0, 0, 0, 0, 0, 0},
            new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0}
        }) {
            ByteBuf encoded = Unpooled.buffer();
            try {
                writeVarInt(encoded, 5);
                encoded.writeBytes(payload);
                assertThrows(RuntimeException.class, () -> decode(protocol, encoded));
            } finally {
                encoded.release();
            }
        }
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            int expectedId,
            BlockPos expectedPosition,
            int expectedProgress) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, new ClientboundBlockDestructionPacket(
                    expectedId, expectedPosition, expectedProgress));

            ByteBuf wireView = encoded.duplicate();
            assertEquals(5, readVarInt(wireView));
            assertEquals(expectedId, readVarInt(wireView));
            assertEquals(expectedPosition.asLong(), wireView.readLong());
            assertEquals(expectedProgress, wireView.readUnsignedByte());
            assertFalse(wireView.isReadable());

            ClientboundBlockDestructionPacket decoded = assertInstanceOf(
                    ClientboundBlockDestructionPacket.class, decode(protocol, encoded));
            assertEquals(expectedId, decoded.getId());
            assertEquals(expectedPosition, decoded.getPos());
            assertEquals(expectedProgress, decoded.getProgress());
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
                5,
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 5, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundBlockDestructionPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundBlockDestructionPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        5,
                        new byte[] {5});
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                return decoded;
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
