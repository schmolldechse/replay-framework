package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.world;

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
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
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
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id008BlockUpdatePacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:block_update";
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesBlockUpdatesAsSafeCheckpointState() {
        var definition = new Id008BlockUpdatePacketDefinition();

        assertEquals(8, definition.packetId());
        assertEquals(0x08, Id008BlockUpdatePacketDefinition.PACKET_ID);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.SAFE,
                definition.classification().playbackScope());
    }

    @Test
    void preservesRepresentativeStateFamiliesWithoutInterpretingProperties() {
        var definition = new Id008BlockUpdatePacketDefinition();
        BlockPos position = new BlockPos(-31, 72, 44);

        assertDefinitionRoundTrip(definition, position, Blocks.AIR.defaultBlockState());
        assertDefinitionRoundTrip(definition, position, Blocks.STONE.defaultBlockState());
        assertDefinitionRoundTrip(definition, position, Blocks.OAK_STAIRS.defaultBlockState());
        assertDefinitionRoundTrip(definition, position, Blocks.WATER.defaultBlockState());
        assertDefinitionRoundTrip(definition, position, Blocks.REDSTONE_WIRE.defaultBlockState());
        assertDefinitionRoundTrip(definition, position, Blocks.WHEAT.defaultBlockState());
        assertDefinitionRoundTrip(definition, position, Blocks.OAK_FENCE.defaultBlockState());
        assertDefinitionRoundTrip(definition, position, Blocks.CHEST.defaultBlockState());
    }

    @Test
    void preservesEveryRegisteredGlobalBlockState() {
        var definition = new Id008BlockUpdatePacketDefinition();
        BlockPos position = new BlockPos(17, -64, -29);
        int visited = 0;

        for (BlockState blockState : Block.BLOCK_STATE_REGISTRY) {
            assertDefinitionRoundTrip(definition, position, blockState);
            visited++;
        }

        assertEquals(Block.BLOCK_STATE_REGISTRY.size(), visited);
        assertTrue(visited > net.minecraft.core.registries.BuiltInRegistries.BLOCK.size());
    }

    @Test
    void validatesTheCanonicalValueModelAndDecodedType() {
        var definition = new Id008BlockUpdatePacketDefinition();

        assertThrows(NullPointerException.class, () ->
                new Id008BlockUpdatePacketDefinition.Value(
                        null, Blocks.STONE.defaultBlockState()));
        assertThrows(NullPointerException.class, () ->
                new Id008BlockUpdatePacketDefinition.Value(BlockPos.ZERO, null));
        assertThrows(IncompatibleAdapterException.class, () ->
                definition.rewrite(new Object(), descriptor(), rewriter()));
    }

    @Test
    void rejectsUnknownNativeStateIdsInProtocolCodec() {
        ProtocolInfo<?> protocol = protocol();

        for (int stateId : new int[] {-1, Block.BLOCK_STATE_REGISTRY.size(), Integer.MAX_VALUE}) {
            assertNativeDecodeFails(protocol, buffer -> {
                buffer.writeLong(BlockPos.ZERO.asLong());
                writeVarInt(buffer, stateId);
            });
        }
    }

    @Test
    void handlesPlaybackAndValidatedEncode() {
        var definition = new Id008BlockUpdatePacketDefinition();
        ClientboundBlockUpdatePacket original = new ClientboundBlockUpdatePacket(
                new BlockPos(10, -64, -20), Blocks.REDSTONE_WIRE.defaultBlockState());

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), rewriter());
        ClientboundBlockUpdatePacket rebuilt = assertInstanceOf(
                ClientboundBlockUpdatePacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertSame(original.getPos(), rebuilt.getPos());
        assertSame(original.getBlockState(), rebuilt.getBlockState());
        assertEquals(8, definition.encode(rebuilt, descriptor(), codec(original)).packetId());
    }

    @Test
    void nativeProtocolCoversEveryStateAndPositionBoundary() {
        ProtocolInfo<?> protocol = protocol();
        BlockPos origin = new BlockPos(0, 64, 0);
        int visited = 0;

        for (BlockState blockState : Block.BLOCK_STATE_REGISTRY) {
            assertNativeRoundTrip(protocol, origin, blockState);
            visited++;
        }
        assertEquals(Block.BLOCK_STATE_REGISTRY.size(), visited);

        for (BlockPos position : new BlockPos[] {
            new BlockPos(-33_554_432, -2_048, -33_554_432),
            BlockPos.ZERO,
            new BlockPos(33_554_431, 2_047, 33_554_431)
        }) {
            assertNativeRoundTrip(protocol, position, Blocks.OAK_STAIRS.defaultBlockState());
        }
    }

    @Test
    void nativeProtocolRejectsIncompleteOrOverlongBodies() {
        ProtocolInfo<?> protocol = protocol();

        assertNativeDecodeFails(protocol, buffer -> {});
        assertNativeDecodeFails(protocol, buffer -> buffer.writeInt(0));
        assertNativeDecodeFails(protocol, buffer -> buffer.writeLong(0));
        assertNativeDecodeFails(protocol, buffer -> {
            buffer.writeLong(0);
            for (int index = 0; index < 5; index++) {
                buffer.writeByte(0x80);
            }
        });
    }

    private static void assertDefinitionRoundTrip(
            Id008BlockUpdatePacketDefinition definition,
            BlockPos expectedPosition,
            BlockState expectedState) {
        var value = definition.read(new ClientboundBlockUpdatePacket(
                expectedPosition, expectedState));
        var rewritten = definition.rewrite(value, rewriter()).value().orElseThrow();
        ClientboundBlockUpdatePacket rebuilt = definition.rebuild(rewritten);

        assertSame(value, rewritten);
        assertSame(expectedPosition, rebuilt.getPos());
        assertSame(expectedState, rebuilt.getBlockState());
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            BlockPos expectedPosition,
            BlockState expectedState) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, new ClientboundBlockUpdatePacket(
                    expectedPosition, expectedState));

            ByteBuf wireView = encoded.duplicate();
            assertEquals(8, readVarInt(wireView));
            assertEquals(expectedPosition.asLong(), wireView.readLong());
            assertEquals(Block.BLOCK_STATE_REGISTRY.getId(expectedState), readVarInt(wireView));
            assertFalse(wireView.isReadable());

            ClientboundBlockUpdatePacket decoded = assertInstanceOf(
                    ClientboundBlockUpdatePacket.class, decode(protocol, encoded));
            assertEquals(expectedPosition, decoded.getPos());
            assertSame(expectedState, decoded.getBlockState());
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
            writeVarInt(encoded, 8);
            bodyWriter.accept(encoded);
            assertThrows(RuntimeException.class, () -> decode(protocol, encoded));
        } finally {
            encoded.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static Paper26_3ReplayPacketRewriter rewriter() {
        return new Paper26_3ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                8,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "paper-26.3/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.SAFE);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 8, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundBlockUpdatePacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundBlockUpdatePacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        8,
                        new byte[] {8});
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
