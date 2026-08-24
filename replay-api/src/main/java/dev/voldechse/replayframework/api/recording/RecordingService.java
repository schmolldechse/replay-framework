package dev.voldechse.replayframework.api.recording;

import dev.voldechse.replayframework.api.id.RecordingSessionId;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Public service for starting and locating recording sessions.
 */
public interface RecordingService {
    /**
     * Starts a recording asynchronously.
     *
     * @param request immutable recording request
     * @return stage completed with the new session
     */
    CompletionStage<RecordingSession> start(RecordingRequest request);

    /**
     * Locates a currently managed recording without performing I/O.
     *
     * @param id recording session identifier
     * @return active session, or empty when it is not managed
     */
    Optional<RecordingSession> active(RecordingSessionId id);
}
