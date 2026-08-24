package dev.voldechse.replayframework.api.playback;

import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Public service for opening and locating viewer-specific playback sessions.
 */
public interface PlaybackService {
    /**
     * Opens a playback asynchronously after the replay and viewer boundary
     * have been validated by the implementation.
     *
     * @param request immutable playback request
     * @return stage completed with a prepared playback session
     */
    CompletionStage<PlaybackSession> open(PlaybackRequest request);

    /**
     * Locates a currently managed playback without performing I/O.
     *
     * @param id playback session identifier
     * @return active session, or empty when it is not managed
     */
    Optional<PlaybackSession> active(PlaybackSessionId id);
}
