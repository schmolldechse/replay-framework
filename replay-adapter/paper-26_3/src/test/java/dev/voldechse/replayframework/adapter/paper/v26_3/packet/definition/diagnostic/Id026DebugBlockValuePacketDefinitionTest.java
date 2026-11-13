package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.diagnostic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundDebugBlockValuePacket;
import net.minecraft.util.debug.DebugBreezeInfo;
import net.minecraft.util.debug.DebugSubscriptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id026DebugBlockValuePacketDefinitionTest {

    private static final String IDENTIFIER = "minecraft:debug/block_value";

    @BeforeAll
    static void bootstrap() {
        DiagnosticPacketDefinitionTestSupport.bootstrap();
    }

    @Test
    void registersAsCapturedBlockedControlData() {
        DiagnosticPacketDefinitionTestSupport.assertDefinition(
                new Id026DebugBlockValuePacketDefinition(),
                DiagnosticPacketDefinitions.definitions(),
                26,
                IDENTIFIER);
    }

    @Test
    void blocksAllRecordedPayloadsBeforePlaybackCodecAccess() {
        DiagnosticPacketDefinitionTestSupport.assertPlaybackBlockedBeforeCodec(
                new Id026DebugBlockValuePacketDefinition());
    }

    @Test
    void nativeCodecPreservesEverySubscriptionClearAndPresentOptionalValues() {
        BlockPos position = new BlockPos(-33_554_432, -2_048, 33_554_431);
        var subscriptions = DiagnosticPacketDefinitionTestSupport.valueSubscriptions();
        assertEquals(15, subscriptions.size());

        for (var subscription : subscriptions) {
            ClientboundDebugBlockValuePacket decoded = assertInstanceOf(
                    ClientboundDebugBlockValuePacket.class,
                    DiagnosticPacketDefinitionTestSupport.roundTrip(
                            new ClientboundDebugBlockValuePacket(
                                    position,
                                    DiagnosticPacketDefinitionTestSupport.emptyUpdate(subscription))));
            assertEquals(position, decoded.blockPos());
            assertEquals(subscription, decoded.update().subscription());
            assertTrue(decoded.update().value().isEmpty());
        }

        DebugBreezeInfo value = new DebugBreezeInfo(
                Optional.of(Integer.MIN_VALUE),
                Optional.of(new BlockPos(12, 70, -18)));
        ClientboundDebugBlockValuePacket decoded = assertInstanceOf(
                ClientboundDebugBlockValuePacket.class,
                DiagnosticPacketDefinitionTestSupport.roundTrip(
                        new ClientboundDebugBlockValuePacket(
                                position, DebugSubscriptions.BREEZES.packUpdate(value))));
        assertEquals(position, decoded.blockPos());
        assertEquals(DebugSubscriptions.BREEZES, decoded.update().subscription());
        assertEquals(Optional.of(value), decoded.update().value());
    }
}
