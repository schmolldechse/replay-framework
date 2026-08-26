package dev.voldechse.replayframework.api;

import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataService;
import dev.voldechse.replayframework.api.playback.PlaybackService;
import dev.voldechse.replayframework.api.recording.RecordingService;
import dev.voldechse.replayframework.api.replay.ReplayService;

/**
 * Public service facade of the Replay Framework.
 *
 * <p>The facade contains no lifecycle method. The Paper runtime owns creation,
 * installation and shutdown of its implementation.</p>
 */
public interface ReplayFramework {
    /**
     * Returns the request defaults derived from the installed runtime
     * configuration.
     *
     * @return immutable recording and playback defaults
     */
    ReplayDefaults defaults();

    /**
     * Returns the recording service.
     *
     * @return recording service
     */
    RecordingService recordings();

    /**
     * Returns the individual playback service.
     *
     * @return playback service
     */
    PlaybackService playbacks();

    /**
     * Returns the replay catalog service.
     *
     * @return replay service
     */
    ReplayService replays();

    /**
     * Returns the typed metadata service.
     *
     * @return metadata service
     */
    ReplayMetadataService metadata();

    /**
     * Returns the read-only event publisher.
     *
     * @return event publisher
     */
    ReplayEventPublisher events();
}
