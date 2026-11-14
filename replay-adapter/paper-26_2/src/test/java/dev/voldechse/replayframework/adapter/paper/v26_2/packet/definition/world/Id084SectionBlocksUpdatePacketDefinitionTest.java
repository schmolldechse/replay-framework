package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2CheckpointEncoder;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id084SectionBlocksUpdatePacketDefinitionTest {

    private static final int PACKET_ID = 84;
    private static final String TYPE_NAME = "play.clientbound.minecraft:section_blocks_update";
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
    void classifiesSectionChangesAsSafeCheckpointRelevantWorldState() {
        var definition = new Id084SectionBlocksUpdatePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x54, Id084SectionBlocksUpdatePacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:section_blocks_update",
                Id084SectionBlocksUpdatePacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.SAFE,
                definition.classification().playbackScope());
        assertEquals(
                Paper26_2CheckpointEncoder.CheckpointPacketFamily.BLOCK_ENTITY,
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
        assertInstanceOf(Id084SectionBlocksUpdatePacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id084SectionBlocksUpdatePacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, descriptor.playbackScope());
    }

    @Test
    void preservesEmptySectionsAndOrderedChangesIncludingRepeatedPositions() {
        var definition = new Id084SectionBlocksUpdatePacketDefinition();
        SectionPos sectionPos = SectionPos.of(-17, -4, 31);
        var empty = new Id084SectionBlocksUpdatePacketDefinition.Value(sectionPos, List.of());
        assertDefinitionRoundTrip(definition, empty);

        var changes = List.of(
                change(0x000, Blocks.AIR.defaultBlockState()),
                change(0xFFF, Blocks.STONE.defaultBlockState()),
                change(0x5A3, Blocks.WATER.defaultBlockState()),
                change(0x000, Blocks.REDSTONE_WIRE.defaultBlockState()));
        assertDefinitionRoundTrip(
                definition, new Id084SectionBlocksUpdatePacketDefinition.Value(sectionPos, changes));
    }

    @Test
    void preservesAll4096PackedLocalPositions() {
        var definition = new Id084SectionBlocksUpdatePacketDefinition();
        SectionPos sectionPos = SectionPos.of(-2, 4, -5);
        List<BlockState> states = List.of(
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
                Blocks.OAK_STAIRS.defaultBlockState());
        List<Id084SectionBlocksUpdatePacketDefinition.BlockChange> changes = new ArrayList<>(4096);
        for (int localPosition = 0; localPosition <= 0xFFF; localPosition++) {
            changes.add(change(localPosition, states.get(localPosition % states.size())));
        }

        assertNativeProtocolRoundTrip(
                protocol(), new Id084SectionBlocksUpdatePacketDefinition.Value(sectionPos, changes));
    }

    @Test
    void preservesEveryRegisteredGlobalBlockState() {
        ProtocolInfo<?> protocol = protocol();
        List<BlockState> states = new ArrayList<>();
        Block.BLOCK_STATE_REGISTRY.forEach(states::add);
        assertEquals(Block.BLOCK_STATE_REGISTRY.size(), states.size());

        for (int start = 0; start < states.size(); start += 4096) {
            int end = Math.min(states.size(), start + 4096);
            List<Id084SectionBlocksUpdatePacketDefinition.BlockChange> changes =
                    new ArrayList<>(end - start);
            for (int index = start; index < end; index++) {
                changes.add(change(index - start, states.get(index)));
            }
            assertNativeProtocolRoundTrip(
                    protocol,
                    new Id084SectionBlocksUpdatePacketDefinition.Value(
                            SectionPos.of(start / 4096, -start / 4096, 1), changes));
        }
    }

    @Test
    void preservesSectionCoordinateBoundariesInNativeProtocol() {
        int[][] coordinates = {
            {-2_097_152, -524_288, -2_097_152},
            {-1, -1, -1},
            {0, 0, 0},
            {2_097_151, 524_287, 2_097_151}
        };
        var changes = List.of(change(0x123, Blocks.OAK_STAIRS.defaultBlockState()));
        for (int[] coordinate : coordinates) {
            assertNativeProtocolRoundTrip(
                    protocol(),
                    new Id084SectionBlocksUpdatePacketDefinition.Value(
                            SectionPos.of(coordinate[0], coordinate[1], coordinate[2]), changes));
        }
    }

    @Test
    void validatesImmutableValuesAndRetainsDuplicateUpdates() {
        var definition = new Id084SectionBlocksUpdatePacketDefinition();
        var mutableChanges = new ArrayList<>(List.of(
                change(0x321, Blocks.STONE.defaultBlockState()),
                change(0x321, Blocks.WATER.defaultBlockState())));
        var value = new Id084SectionBlocksUpdatePacketDefinition.Value(
                SectionPos.of(0, 0, 0), mutableChanges);
        mutableChanges.clear();

        assertEquals(2, value.changes().size());
        assertEquals(value.changes().getFirst().localPosition(),
                value.changes().getLast().localPosition());
        assertThrows(UnsupportedOperationException.class,
                () -> value.changes().add(change(0, Blocks.AIR.defaultBlockState())));
        assertThrows(NullPointerException.class,
                () -> new Id084SectionBlocksUpdatePacketDefinition.Value(null, List.of()));
        assertThrows(NullPointerException.class,
                () -> new Id084SectionBlocksUpdatePacketDefinition.Value(SectionPos.of(0, 0, 0), null));
        assertThrows(NullPointerException.class,
                () -> new Id084SectionBlocksUpdatePacketDefinition.Value(
                        SectionPos.of(0, 0, 0), java.util.Arrays.asList((
                                Id084SectionBlocksUpdatePacketDefinition.BlockChange) null)));
        assertThrows(IllegalArgumentException.class,
                () -> change(-1, Blocks.AIR.defaultBlockState()));
        assertThrows(IllegalArgumentException.class,
                () -> change(0x1000, Blocks.AIR.defaultBlockState()));
        assertThrows(NullPointerException.class, () -> change(0, null));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.rewrite(new Object(), descriptor(), rewriter()));
    }

    @Test
    void rejectsUnknownBlockStateIdsFromTheNativePacketDecoder() {
        ProtocolInfo<?> protocol = protocol();
        int unknownStateId = Block.BLOCK_STATE_REGISTRY.size();
        ByteBuf encoded = Unpooled.buffer();
        try {
            writeVarInt(encoded, PACKET_ID);
            encoded.writeLong(SectionPos.of(0, 0, 0).asLong());
            writeVarInt(encoded, 1);
            writeVarLong(encoded, (long) unknownStateId << 12 | 0x456);

            ClientboundSectionBlocksUpdatePacket decoded = assertInstanceOf(
                    ClientboundSectionBlocksUpdatePacket.class, decode(protocol, encoded));
            assertThrows(IncompatibleAdapterException.class,
                    () -> new Id084SectionBlocksUpdatePacketDefinition().read(decoded));
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeProtocolRejectsTruncatedRecordsAndInvalidUpdateCounts() {
        ProtocolInfo<?> protocol = protocol();
        assertNativeDecodeFails(protocol, ignored -> {});
        assertNativeDecodeFails(protocol, buffer -> buffer.writeInt(0));
        assertNativeDecodeFails(protocol, buffer -> buffer.writeLong(0));
        assertNativeDecodeFails(protocol, buffer -> {
            buffer.writeLong(0);
            writeVarInt(buffer, 1);
        });
        assertNativeDecodeFails(protocol, buffer -> {
            buffer.writeLong(0);
            writeVarInt(buffer, 1);
            buffer.writeByte(0x80);
        });
        assertNativeDecodeFails(protocol, buffer -> {
            buffer.writeLong(0);
            writeVarInt(buffer, -1);
        });

        var definition = new Id084SectionBlocksUpdatePacketDefinition();
        ByteBuf complete = encodePacket(protocol, packet(new Id084SectionBlocksUpdatePacketDefinition.Value(
                SectionPos.of(2, -3, 4),
                List.of(change(0x234, Block.BLOCK_STATE_REGISTRY.byId(
                        Block.BLOCK_STATE_REGISTRY.size() - 1))))));
        try {
            ByteBuf wire = complete.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            byte[] payload = bytes(wire);
            for (int length = 0; length < payload.length; length++) {
                int prefixLength = length;
                assertNativeDecodeFails(
                        protocol, buffer -> buffer.writeBytes(payload, 0, prefixLength));
            }
            assertEquals(1, definition.read(assertInstanceOf(
                    ClientboundSectionBlocksUpdatePacket.class, decode(protocol, complete.duplicate())))
                    .changes().size());
        } finally {
            complete.release();
        }
    }

    @Test
    void playbackSendsTheSameWorldChangesAndValidatesItsDescriptor() {
        var definition = new Id084SectionBlocksUpdatePacketDefinition();
        var original = packet(new Id084SectionBlocksUpdatePacketDefinition.Value(
                SectionPos.of(-1, 7, 12),
                List.of(
                        change(0x120, Blocks.OAK_STAIRS.defaultBlockState()),
                        change(0x120, Blocks.WATER.defaultBlockState()))));

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), rewriter());
        ClientboundSectionBlocksUpdatePacket rebuilt = assertInstanceOf(
                ClientboundSectionBlocksUpdatePacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(definition.read(original), definition.read(rebuilt));
        assertEquals(PACKET_ID,
                definition.encode(rebuilt, descriptor(), codec(rebuilt)).packetId());
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(),
                descriptor(PACKET_ID + 1, true, PacketDescriptor.PlaybackScope.SAFE),
                codec(original),
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(),
                descriptor(PACKET_ID, true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                codec(original),
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.encode(
                original,
                descriptor(PACKET_ID, false, PacketDescriptor.PlaybackScope.SAFE),
                codec(original)));
    }

    private static void assertDefinitionRoundTrip(
            Id084SectionBlocksUpdatePacketDefinition definition,
            Id084SectionBlocksUpdatePacketDefinition.Value expected) {
        ClientboundSectionBlocksUpdatePacket original = packet(expected);
        var value = definition.read(original);
        var rewritten = definition.rewrite(value, rewriter()).value().orElseThrow();
        assertSame(value, rewritten);
        assertEquals(expected, value);
        assertEquals(expected, definition.read(definition.rebuild(rewritten)));
    }

    private static void assertNativeProtocolRoundTrip(
            ProtocolInfo<?> protocol,
            Id084SectionBlocksUpdatePacketDefinition.Value expected) {
        ClientboundSectionBlocksUpdatePacket original = packet(expected);
        ByteBuf encoded = encodePacket(protocol, original);
        try {
            byte[] expectedWire = bytes(encoded);
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, wire.readVarInt());
            assertEquals(expected.sectionPos(), SectionPos.STREAM_CODEC.decode(wire));
            assertEquals(expected.changes().size(), wire.readVarInt());
            for (var change : expected.changes()) {
                long packed = wire.readVarLong();
                assertEquals(pack(change), packed);
            }
            assertFalse(wire.isReadable());

            ClientboundSectionBlocksUpdatePacket decoded = assertInstanceOf(
                    ClientboundSectionBlocksUpdatePacket.class, decode(protocol, encoded));
            var definition = new Id084SectionBlocksUpdatePacketDefinition();
            assertEquals(expected, definition.read(decoded));
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encodePacket(protocol, definition.rebuild(expected));
            try {
                assertArrayEquals(expectedWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static Id084SectionBlocksUpdatePacketDefinition.BlockChange change(
            int localPosition, BlockState state) {
        return new Id084SectionBlocksUpdatePacketDefinition.BlockChange(localPosition, state);
    }

    private static ClientboundSectionBlocksUpdatePacket packet(
            Id084SectionBlocksUpdatePacketDefinition.Value value) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SectionPos.STREAM_CODEC.encode(buffer, value.sectionPos());
            buffer.writeVarInt(value.changes().size());
            for (var change : value.changes()) {
                buffer.writeVarLong(pack(change));
            }
            return ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static long pack(Id084SectionBlocksUpdatePacketDefinition.BlockChange change) {
        return (long) Block.BLOCK_STATE_REGISTRY.getId(change.state()) << 12
                | change.localPosition();
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
        return descriptor(PACKET_ID, true, PacketDescriptor.PlaybackScope.SAFE);
    }

    private static PacketDescriptor descriptor(
            int packetId, boolean replayable, PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                replayable,
                true,
                "paper-26.2/" + TYPE_NAME,
                scope);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundSectionBlocksUpdatePacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundSectionBlocksUpdatePacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        PACKET_ID,
                        new byte[0]);
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                return decoded;
            }
        };
    }

    private static void assertNativeDecodeFails(
            ProtocolInfo<?> protocol,
            Consumer<ByteBuf> bodyWriter) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            writeVarInt(encoded, PACKET_ID);
            bodyWriter.accept(encoded);
            assertThrows(RuntimeException.class, () -> decode(protocol, encoded));
        } finally {
            encoded.release();
        }
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

    private static void writeVarLong(ByteBuf buffer, long value) {
        long remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            buffer.writeByte((int) (remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer.writeByte((int) remaining);
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
