package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundGameTestHighlightPosPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id040GameTestHighlightPosPacketDefinitionTest {

    private static final int PACKET_ID = 40;
    private static final String IDENTIFIER = "minecraft:game_test_highlight_pos";
    private static final String TYPE_NAME = "play.clientbound." + IDENTIFIER;

    @BeforeAll
    static void bootstrap() {
        DiagnosticPacketDefinitionTestSupport.bootstrap();
    }

    @Test
    void registersAsCapturedBlockedControlDataInTheDomainCatalogAndRegistry() {
        var definition = new Id040GameTestHighlightPosPacketDefinition();
        DiagnosticPacketDefinitionTestSupport.assertDefinition(
                definition,
                DiagnosticPacketDefinitions.definitions(),
                PACKET_ID,
                IDENTIFIER);

        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id040GameTestHighlightPosPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.CONTROL, descriptor.disposition());
        assertEquals(PacketDescriptor.PlaybackScope.UNSAFE, descriptor.playbackScope());
        assertFalse(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
    }

    @Test
    void blocksValidLookingAndMalformedRecordedPayloadsBeforeCodecAccess() {
        DiagnosticPacketDefinitionTestSupport.assertPlaybackBlockedBeforeCodec(
                new Id040GameTestHighlightPosPacketDefinition());
    }

    @Test
    void nativeCodecPreservesAbsoluteAndRelativePositionsAcrossPackedBoundaries() {
        int maxHorizontal = BlockPos.MAX_HORIZONTAL_COORDINATE;
        int minHorizontal = -maxHorizontal - 1;
        int maxY = (1 << (BlockPos.PACKED_Y_LENGTH - 1)) - 1;
        int minY = -(1 << (BlockPos.PACKED_Y_LENGTH - 1));
        List<ClientboundGameTestHighlightPosPacket> packets = List.of(
                new ClientboundGameTestHighlightPosPacket(BlockPos.ZERO, BlockPos.ZERO),
                new ClientboundGameTestHighlightPosPacket(
                        new BlockPos(123456, 320, -654321), new BlockPos(5, 7, -9)),
                new ClientboundGameTestHighlightPosPacket(
                        new BlockPos(-12345, -64, 67890), new BlockPos(-3, -7, 9)),
                new ClientboundGameTestHighlightPosPacket(
                        new BlockPos(maxHorizontal, maxY, maxHorizontal),
                        new BlockPos(minHorizontal, minY, minHorizontal)),
                new ClientboundGameTestHighlightPosPacket(
                        new BlockPos(minHorizontal, minY, minHorizontal),
                        new BlockPos(maxHorizontal, maxY, maxHorizontal)));

        for (ClientboundGameTestHighlightPosPacket expected : packets) {
            ByteBuf wire = Unpooled.wrappedBuffer(
                    DiagnosticPacketDefinitionTestSupport.encodeToBytes(expected));
            try {
                assertEquals(PACKET_ID, DiagnosticPacketDefinitionTestSupport.readVarInt(wire));
                assertEquals(expected.absolutePos().asLong(), wire.readLong());
                assertEquals(expected.relativePos().asLong(), wire.readLong());
                assertFalse(wire.isReadable());
            } finally {
                wire.release();
            }

            ClientboundGameTestHighlightPosPacket decoded = assertInstanceOf(
                    ClientboundGameTestHighlightPosPacket.class,
                    DiagnosticPacketDefinitionTestSupport.roundTrip(expected));
            assertEquals(expected.absolutePos(), decoded.absolutePos());
            assertEquals(expected.relativePos(), decoded.relativePos());
        }
    }

    @Test
    void nativeCodecRejectsMissingOrTruncatedPositions() {
        byte[] complete = DiagnosticPacketDefinitionTestSupport.encodeToBytes(
                new ClientboundGameTestHighlightPosPacket(
                        new BlockPos(1, 2, 3), new BlockPos(-4, -5, -6)));

        assertMalformed(new byte[] {(byte) PACKET_ID});
        assertMalformed(Arrays.copyOf(complete, 1 + Long.BYTES));
        assertMalformed(Arrays.copyOf(complete, complete.length - 1));
    }

    private static void assertMalformed(byte[] wireBytes) {
        ByteBuf input = Unpooled.wrappedBuffer(wireBytes);
        try {
            assertThrows(RuntimeException.class, () -> decode(protocol(), input));
        } finally {
            input.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf input) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(input);
    }
}
