package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id007BlockEventPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:block_event";
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesBlockEventsAsSafeEphemeralState() {
        var definition = new Id007BlockEventPacketDefinition();

        assertEquals(7, definition.packetId());
        assertEquals(0x07, Id007BlockEventPacketDefinition.PACKET_ID);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.SAFE,
                definition.classification().playbackScope());
    }

    @Test
    void preservesDocumentedBlockActionFamiliesWithoutInterpretingTheirParameters() {
        var definition = new Id007BlockEventPacketDefinition();
        BlockPos position = new BlockPos(-31, 72, 44);

        assertDefinitionRoundTrip(definition, position, Blocks.NOTE_BLOCK, 0, 24);
        assertDefinitionRoundTrip(definition, position, Blocks.PISTON, 0, 5);
        assertDefinitionRoundTrip(definition, position, Blocks.CHEST, 1, 27);
        assertDefinitionRoundTrip(definition, position, Blocks.SPAWNER, 1, 0);
        assertDefinitionRoundTrip(definition, position, Blocks.END_GATEWAY, 1, 0);
        assertDefinitionRoundTrip(definition, position, Blocks.SHULKER_BOX, 1, 63);
        assertDefinitionRoundTrip(definition, position, Blocks.BELL, 1, 5);
        assertDefinitionRoundTrip(definition, position, Blocks.DECORATED_POT, 1, 0);
    }

    @Test
    void preservesEveryUnsignedActionValueAndEveryRegisteredBlock() {
        var definition = new Id007BlockEventPacketDefinition();
        BlockPos position = new BlockPos(17, -64, -29);

        for (int actionId = 0; actionId <= 0xFF; actionId++) {
            assertDefinitionRoundTrip(definition, position, Blocks.NOTE_BLOCK, actionId, 91);
        }
        for (int actionParameter = 0; actionParameter <= 0xFF; actionParameter++) {
            assertDefinitionRoundTrip(
                    definition, position, Blocks.NOTE_BLOCK, 37, actionParameter);
        }
        BuiltInRegistries.BLOCK.stream().forEach(block ->
                assertDefinitionRoundTrip(definition, position, block, 255, 255));
    }

    @Test
    void canonicalizesInMemoryConstructorValuesToTheirWireBytes() {
        var definition = new Id007BlockEventPacketDefinition();

        assertEquals(0xFF, definition.read(new ClientboundBlockEventPacket(
                BlockPos.ZERO, Blocks.NOTE_BLOCK, -1, 0)).actionId());
        assertEquals(0, definition.read(new ClientboundBlockEventPacket(
                BlockPos.ZERO, Blocks.NOTE_BLOCK, 0, 0x100)).actionParameter());
        var extremes = definition.read(new ClientboundBlockEventPacket(
                BlockPos.ZERO, Blocks.NOTE_BLOCK, Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(0, extremes.actionId());
        assertEquals(0xFF, extremes.actionParameter());
    }

    @Test
    void validatesTheCanonicalValueModelAndDecodedType() {
        var definition = new Id007BlockEventPacketDefinition();

        assertThrows(NullPointerException.class, () ->
                new Id007BlockEventPacketDefinition.Value(null, 0, 0, Blocks.NOTE_BLOCK));
        assertThrows(NullPointerException.class, () ->
                new Id007BlockEventPacketDefinition.Value(BlockPos.ZERO, 0, 0, null));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id007BlockEventPacketDefinition.Value(BlockPos.ZERO, -1, 0, Blocks.NOTE_BLOCK));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id007BlockEventPacketDefinition.Value(BlockPos.ZERO, 0x100, 0, Blocks.NOTE_BLOCK));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id007BlockEventPacketDefinition.Value(BlockPos.ZERO, 0, -1, Blocks.NOTE_BLOCK));
        assertThrows(IncompatibleAdapterException.class, () ->
                new Id007BlockEventPacketDefinition.Value(BlockPos.ZERO, 0, 0x100, Blocks.NOTE_BLOCK));
        assertThrows(IncompatibleAdapterException.class, () ->
                definition.rewrite(new Object(), descriptor(), rewriter()));
    }

    @Test
    void handlesPlaybackAndValidatedEncode() {
        var definition = new Id007BlockEventPacketDefinition();
        ClientboundBlockEventPacket original = new ClientboundBlockEventPacket(
                new BlockPos(10, -64, -20), Blocks.BELL, 1, 5);

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), rewriter());
        ClientboundBlockEventPacket rebuilt = assertInstanceOf(
                ClientboundBlockEventPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(original.getPos(), rebuilt.getPos());
        assertSame(original.getBlock(), rebuilt.getBlock());
        assertEquals(original.getB0(), rebuilt.getB0());
        assertEquals(original.getB1(), rebuilt.getB1());
        assertEquals(7, definition.encode(rebuilt, descriptor(), codec(original)).packetId());
    }

    @Test
    void nativeProtocolCoversEveryWireValueRegisteredBlockAndPositionBoundary() {
        ProtocolInfo<?> protocol = protocol();
        BlockPos origin = new BlockPos(0, 64, 0);

        for (int actionId = 0; actionId <= 0xFF; actionId++) {
            assertNativeRoundTrip(protocol, origin, Blocks.NOTE_BLOCK, actionId, 91);
        }
        for (int actionParameter = 0; actionParameter <= 0xFF; actionParameter++) {
            assertNativeRoundTrip(protocol, origin, Blocks.NOTE_BLOCK, 37, actionParameter);
        }
        BuiltInRegistries.BLOCK.stream().forEach(block ->
                assertNativeRoundTrip(protocol, origin, block, 255, 255));
        for (BlockPos position : new BlockPos[] {
            new BlockPos(-33_554_432, -2_048, -33_554_432),
            BlockPos.ZERO,
            new BlockPos(33_554_431, 2_047, 33_554_431)
        }) {
            assertNativeRoundTrip(protocol, position, Blocks.DECORATED_POT, 255, 0);
        }
    }

    @Test
    void nativeProtocolRejectsIncompleteOrInvalidBodies() {
        ProtocolInfo<?> protocol = protocol();

        assertNativeDecodeFails(protocol, buffer -> {});
        assertNativeDecodeFails(protocol, buffer -> buffer.writeInt(0));
        assertNativeDecodeFails(protocol, buffer -> buffer.writeLong(0));
        assertNativeDecodeFails(protocol, buffer -> {
            buffer.writeLong(0);
            buffer.writeByte(0);
        });
        assertNativeDecodeFails(protocol, buffer -> {
            buffer.writeLong(0);
            buffer.writeByte(0);
            buffer.writeByte(0);
        });
        assertNativeDecodeFails(protocol, buffer -> {
            buffer.writeLong(0);
            buffer.writeByte(0);
            buffer.writeByte(0);
            for (int index = 0; index < 5; index++) {
                buffer.writeByte(0x80);
            }
        });
    }

    private static void assertDefinitionRoundTrip(
            Id007BlockEventPacketDefinition definition,
            BlockPos expectedPosition,
            Block expectedBlock,
            int expectedActionId,
            int expectedActionParameter) {
        var value = definition.read(new ClientboundBlockEventPacket(
                expectedPosition, expectedBlock, expectedActionId, expectedActionParameter));
        var rewritten = definition.rewrite(value, rewriter()).value().orElseThrow();
        ClientboundBlockEventPacket rebuilt = definition.rebuild(rewritten);

        assertSame(value, rewritten);
        assertSame(expectedPosition, rebuilt.getPos());
        assertSame(expectedBlock, rebuilt.getBlock());
        assertEquals(expectedActionId, rebuilt.getB0());
        assertEquals(expectedActionParameter, rebuilt.getB1());
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            BlockPos expectedPosition,
            Block expectedBlock,
            int expectedActionId,
            int expectedActionParameter) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, new ClientboundBlockEventPacket(
                    expectedPosition, expectedBlock, expectedActionId, expectedActionParameter));

            ByteBuf wireView = encoded.duplicate();
            assertEquals(7, readVarInt(wireView));
            assertEquals(expectedPosition.asLong(), wireView.readLong());
            assertEquals(expectedActionId, wireView.readUnsignedByte());
            assertEquals(expectedActionParameter, wireView.readUnsignedByte());
            assertEquals(BuiltInRegistries.BLOCK.getId(expectedBlock), readVarInt(wireView));
            assertFalse(wireView.isReadable());

            ClientboundBlockEventPacket decoded = assertInstanceOf(
                    ClientboundBlockEventPacket.class, decode(protocol, encoded));
            assertEquals(expectedPosition, decoded.getPos());
            assertSame(expectedBlock, decoded.getBlock());
            assertEquals(expectedActionId, decoded.getB0());
            assertEquals(expectedActionParameter, decoded.getB1());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertNativeDecodeFails(
            ProtocolInfo<?> protocol,
            Consumer<ByteBuf> bodyWriter) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            writeVarInt(encoded, 7);
            bodyWriter.accept(encoded);
            assertThrows(RuntimeException.class, () -> decode(protocol, encoded));
        } finally {
            encoded.release();
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
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                7,
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.SAFE);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 7, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundBlockEventPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundBlockEventPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        7,
                        new byte[] {7});
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
