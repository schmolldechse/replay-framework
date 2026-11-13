package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import net.minecraft.network.protocol.game.ClientboundDebugEventPacket;
import net.minecraft.util.debug.DebugGameEventListenerInfo;
import net.minecraft.util.debug.DebugSubscriptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id029DebugEventPacketDefinitionTest {

    private static final String IDENTIFIER = "minecraft:debug/event";

    @BeforeAll
    static void bootstrap() {
        DiagnosticPacketDefinitionTestSupport.bootstrap();
    }

    @Test
    void registersAsCapturedBlockedControlData() {
        DiagnosticPacketDefinitionTestSupport.assertDefinition(
                new Id029DebugEventPacketDefinition(),
                DiagnosticPacketDefinitions.definitions(),
                29,
                IDENTIFIER);
    }

    @Test
    void blocksAllRecordedPayloadsBeforePlaybackCodecAccess() {
        DiagnosticPacketDefinitionTestSupport.assertPlaybackBlockedBeforeCodec(
                new Id029DebugEventPacketDefinition());
    }

    @Test
    void nativeCodecPreservesMandatorySubscriptionEventData() {
        var event = DebugSubscriptions.GAME_EVENT_LISTENERS.packEvent(
                new DebugGameEventListenerInfo(73));
        ClientboundDebugEventPacket decoded = assertInstanceOf(
                ClientboundDebugEventPacket.class,
                DiagnosticPacketDefinitionTestSupport.roundTrip(new ClientboundDebugEventPacket(event)));

        assertEquals(DebugSubscriptions.GAME_EVENT_LISTENERS, decoded.event().subscription());
        assertEquals(event.value(), decoded.event().value());
    }
}
