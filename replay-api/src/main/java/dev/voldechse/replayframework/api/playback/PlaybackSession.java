package dev.voldechse.replayframework.api.playback;

import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * One viewer's independent playback timeline.
 */
public interface PlaybackSession {
    /**
     * Returns this playback session's identifier.
     *
     * @return playback session identifier
     */
    PlaybackSessionId id();

    /**
     * Returns the replay being viewed.
     *
     * @return replay identifier
     */
    ReplayId replayId();

    /**
     * Returns the viewer UUID captured at open time.
     *
     * @return viewer UUID
     */
    UUID viewerId();

    /**
     * Returns an immutable point-in-time timeline snapshot.
     *
     * @return current snapshot
     */
    PlaybackSnapshot snapshot();

    /**
     * Requests playback. The call is non-blocking and thread-safe.
     */
    void play();

    /**
     * Requests pause. The call is non-blocking and thread-safe.
     */
    void pause();

    /**
     * Changes the session-local speed without changing position.
     *
     * @param speed supported speed
     */
    void speed(PlaybackSpeed speed);

    /**
     * Seeks to the beginning while preserving the current play intent.
     *
     * @return stage completed with the applied snapshot
     */
    CompletionStage<PlaybackSnapshot> restart();

    /**
     * Seeks to an absolute position. Implementations clamp the target to the
     * replay duration.
     *
     * @param position requested absolute position
     * @return stage completed with the applied snapshot
     */
    CompletionStage<PlaybackSnapshot> seekTo(Duration position);

    /**
     * Seeks relative to the current position. Implementations clamp the target
     * to the replay duration.
     *
     * @param delta relative position change
     * @return stage completed with the applied snapshot
     */
    CompletionStage<PlaybackSnapshot> seekBy(Duration delta);

    /**
     * Closes this session and releases internal playback resources. Repeated
     * calls are idempotent.
     *
     * @return stage completed with a closed snapshot
     */
    CompletionStage<PlaybackSnapshot> close();
}
