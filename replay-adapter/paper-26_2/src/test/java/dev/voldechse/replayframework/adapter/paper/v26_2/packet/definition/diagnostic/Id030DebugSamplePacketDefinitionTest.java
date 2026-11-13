package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.diagnostic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import net.minecraft.network.protocol.game.ClientboundDebugSamplePacket;
import net.minecraft.util.debugchart.RemoteDebugSampleType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id030DebugSamplePacketDefinitionTest {

    private static final String IDENTIFIER = "minecraft:debug_sample";

    @BeforeAll
    static void bootstrap() {
        DiagnosticPacketDefinitionTestSupport.bootstrap();
    }

    @Test
    void registersAsCapturedBlockedControlData() {
        DiagnosticPacketDefinitionTestSupport.assertDefinition(
                new Id030DebugSamplePacketDefinition(),
                DiagnosticPacketDefinitions.definitions(),
                30,
                IDENTIFIER);
    }

    @Test
    void blocksAllRecordedPayloadsBeforePlaybackCodecAccess() {
        DiagnosticPacketDefinitionTestSupport.assertPlaybackBlockedBeforeCodec(
                new Id030DebugSamplePacketDefinition());
    }

    @Test
    void nativeCodecPreservesAllFourTickTimeMetrics() {
        long[] samples = {Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE};
        ClientboundDebugSamplePacket decoded = assertInstanceOf(
                ClientboundDebugSamplePacket.class,
                DiagnosticPacketDefinitionTestSupport.roundTrip(
                        new ClientboundDebugSamplePacket(samples, RemoteDebugSampleType.TICK_TIME)));

        assertArrayEquals(samples, decoded.sample());
        assertEquals(RemoteDebugSampleType.TICK_TIME, decoded.debugSampleType());
    }
}
