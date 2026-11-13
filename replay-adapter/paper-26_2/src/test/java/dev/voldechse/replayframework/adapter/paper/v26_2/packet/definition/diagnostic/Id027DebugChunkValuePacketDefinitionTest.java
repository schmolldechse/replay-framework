package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.protocol.game.ClientboundDebugChunkValuePacket;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id027DebugChunkValuePacketDefinitionTest {

    private static final String IDENTIFIER = "minecraft:debug/chunk_value";

    @BeforeAll
    static void bootstrap() {
        DiagnosticPacketDefinitionTestSupport.bootstrap();
    }

    @Test
    void registersAsCapturedBlockedControlData() {
        DiagnosticPacketDefinitionTestSupport.assertDefinition(
                new Id027DebugChunkValuePacketDefinition(),
                DiagnosticPacketDefinitions.definitions(),
                27,
                IDENTIFIER);
    }

    @Test
    void blocksAllRecordedPayloadsBeforePlaybackCodecAccess() {
        DiagnosticPacketDefinitionTestSupport.assertPlaybackBlockedBeforeCodec(
                new Id027DebugChunkValuePacketDefinition());
    }

    @Test
    void nativeCodecPreservesEverySubscriptionClearAndChunkCoordinateOrder() {
        ChunkPos position = new ChunkPos(Integer.MIN_VALUE, Integer.MAX_VALUE);
        var subscriptions = DiagnosticPacketDefinitionTestSupport.valueSubscriptions();
        assertEquals(15, subscriptions.size());

        for (var subscription : subscriptions) {
            ClientboundDebugChunkValuePacket decoded = assertInstanceOf(
                    ClientboundDebugChunkValuePacket.class,
                    DiagnosticPacketDefinitionTestSupport.roundTrip(
                            new ClientboundDebugChunkValuePacket(
                                    position,
                                    DiagnosticPacketDefinitionTestSupport.emptyUpdate(subscription))));
            assertEquals(position, decoded.chunkPos());
            assertEquals(subscription, decoded.update().subscription());
            assertTrue(decoded.update().value().isEmpty());
        }

        byte[] bytes = DiagnosticPacketDefinitionTestSupport.encodeToBytes(
                new ClientboundDebugChunkValuePacket(
                        position,
                        DiagnosticPacketDefinitionTestSupport.emptyUpdate(subscriptions.getFirst())));
        ByteBuf wire = Unpooled.wrappedBuffer(bytes);
        try {
            assertEquals(27, DiagnosticPacketDefinitionTestSupport.readVarInt(wire));
            long expected = ((long) position.z() << 32) | Integer.toUnsignedLong(position.x());
            assertEquals(expected, wire.readLong());
        } finally {
            wire.release();
        }
    }
}
