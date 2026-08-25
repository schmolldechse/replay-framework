package dev.voldechse.replayframework.core.recording;

import com.google.inject.Inject;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.recording.RecordingRequest;
import dev.voldechse.replayframework.api.recording.RecordingService;
import dev.voldechse.replayframework.api.recording.RecordingSession;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Public-service adapter that keeps the framework API independent of the
 * coordinator, repository and queue implementation types.
 */
public final class DefaultRecordingService implements RecordingService {
    private final RecordingCoordinator coordinator;

    /** Creates a service backed by one coordinator composition. */
    @Inject
    public DefaultRecordingService(RecordingCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public CompletionStage<RecordingSession> start(RecordingRequest request) {
        Objects.requireNonNull(request, "request");
        return coordinator.start(request).thenApply(session -> session);
    }

    @Override
    public Optional<RecordingSession> active(RecordingSessionId id) {
        Objects.requireNonNull(id, "id");
        return coordinator.active(id).map(session -> session);
    }

    /** Stops new starts while leaving existing session finalizers usable. */
    public void shutdown() {
        coordinator.shutdown();
    }
}
