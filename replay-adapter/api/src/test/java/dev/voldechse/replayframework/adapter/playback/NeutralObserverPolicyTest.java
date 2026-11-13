package dev.voldechse.replayframework.adapter.playback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NeutralObserverPolicyTest {

    @Test
    void identifiesRecordedChatPresentationPackets() {
        assertTrue(NeutralObserverPolicy.isRecordedChatPresentation("minecraft:player_chat"));
        assertTrue(NeutralObserverPolicy.isRecordedChatPresentation("minecraft:system_chat"));
        assertTrue(NeutralObserverPolicy.isRecordedChatPresentation("minecraft:disguised_chat"));
        assertFalse(NeutralObserverPolicy.isRecordedChatPresentation("minecraft:delete_chat"));
        assertFalse(NeutralObserverPolicy.isRecordedChatPresentation("player_chat"));
    }
}
