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
    /**
     * Returns every player of the replay with its location at the current
     * playback position, across all recorded worlds.
     */
    java.util.List<RecordedPlayer> recordedPlayers();

    /**
     * Returns the recorded world the viewer watches. A replay can span several
     * worlds; the viewer's client shows one of them at a time.
     */
    net.kyori.adventure.key.Key viewedWorld();

    /**
     * Switches the viewer to another recorded world and builds its scene at
     * the current position, as a seek does. The viewer is not moved: the
     * caller places the viewer in a world whose dimension matches the
     * recorded one before switching, because the client applies chunks with
     * the height of its own dimension.
     *
     * @param world a recorded world
     */
    CompletionStage<PlaybackSnapshot> viewWorld(net.kyori.adventure.key.Key world);

    /**
     * Returns the state of a subject at the current position, such as a
     * player's inventory or the content of a chested llama. Empty when the
     * subject is not part of the replay at this position.
     *
     * @param subject a player or an entity of the replay
     */
    CompletionStage<java.util.Optional<SubjectState>> state(RecordedSubject subject);

    /**
     * Returns what the scene shows of an entity the viewer's client knows by
     * the given ID, for example the entity a viewer clicked.
     *
     * @param clientEntityId the entity ID on the viewer's client
     */
    java.util.Optional<RecordedEntity> entity(int clientEntityId);

    /** Returns every entity of the viewed world at the current position. */
    java.util.List<RecordedEntity> entities();

    CompletionStage<PlaybackSnapshot> close();
}
