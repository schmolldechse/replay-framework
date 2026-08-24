package dev.voldechse.replayframework.api.recording;

/**
 * Lifecycle status of a recording and its replay catalog entry.
 *
 * <p>Only {@link #AVAILABLE} represents a replay that is ready for playback.
 * The transition rules are owned by the recording coordinator and repository,
 * not by this enum.</p>
 */
public enum RecordingStatus {
    /** The recording request is being initialized. */
    INITIALIZING,
    /** The recording accepts captured packets. */
    RECORDING,
    /** The recording is draining and publishing its artifacts. */
    FINALIZING,
    /** The replay is fully published and available for playback. */
    AVAILABLE,
    /** The recording or publication failed and is not playable. */
    FAILED,
    /** The replay is being deleted. */
    DELETING
}
