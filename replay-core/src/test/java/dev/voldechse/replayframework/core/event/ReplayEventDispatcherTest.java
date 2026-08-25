package dev.voldechse.replayframework.core.event;

import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ReplayEventDispatcherTest {

    @Test
    void listenerFailureDoesNotPreventAnotherListenerFromReceivingTheEvent() {
        ReplayEventDispatcher dispatcher = new ReplayEventDispatcher(Runnable::run, ignored -> {});
        List<ReplayEventPublisher.ReplayEvent> received = new ArrayList<>();
        ReplayEventPublisher.Subscription failing = dispatcher.subscribe(event -> {
            throw new IllegalStateException("listener failure");
        });
        ReplayEventPublisher.Subscription observing = dispatcher.subscribe(received::add);

        dispatcher.recordingStatusChanged(
                RecordingSessionId.random(),
                ReplayId.random(),
                RecordingStatus.INITIALIZING,
                RecordingStatus.RECORDING);

        assertEquals(1, received.size());
        failing.close();
        observing.close();
        dispatcher.close();
    }
}
