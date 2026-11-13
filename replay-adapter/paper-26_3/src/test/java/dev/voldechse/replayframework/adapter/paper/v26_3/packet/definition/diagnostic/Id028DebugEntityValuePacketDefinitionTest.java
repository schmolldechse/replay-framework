package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.protocol.game.ClientboundDebugEntityValuePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id028DebugEntityValuePacketDefinitionTest {

    private static final String IDENTIFIER = "minecraft:debug/entity_value";

    @BeforeAll
    static void bootstrap() {
        DiagnosticPacketDefinitionTestSupport.bootstrap();
    }

    @Test
    void registersAsCapturedBlockedControlData() {
        DiagnosticPacketDefinitionTestSupport.assertDefinition(
                new Id028DebugEntityValuePacketDefinition(),
                DiagnosticPacketDefinitions.definitions(),
                28,
                IDENTIFIER);
    }

    @Test
    void blocksAllRecordedPayloadsBeforePlaybackCodecAccess() {
        DiagnosticPacketDefinitionTestSupport.assertPlaybackBlockedBeforeCodec(
                new Id028DebugEntityValuePacketDefinition());
    }

    @Test
    void nativeCodecPreservesEntityIdBoundariesAndEverySubscriptionClear() {
        var subscriptions = DiagnosticPacketDefinitionTestSupport.valueSubscriptions();
        assertEquals(15, subscriptions.size());

        for (int entityId : new int[] {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE}) {
            for (var subscription : subscriptions) {
                ClientboundDebugEntityValuePacket decoded = assertInstanceOf(
                        ClientboundDebugEntityValuePacket.class,
                        DiagnosticPacketDefinitionTestSupport.roundTrip(
                                new ClientboundDebugEntityValuePacket(
                                        entityId,
                                        DiagnosticPacketDefinitionTestSupport.emptyUpdate(subscription))));
                assertEquals(entityId, decoded.entityId());
                assertEquals(subscription, decoded.update().subscription());
                assertTrue(decoded.update().value().isEmpty());
            }
        }
    }
}
