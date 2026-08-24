package dev.voldechse.replayframework.api.playback;

/**
 * Public lifecycle status of one viewer's playback session.
 */
public enum PlaybackStatus {
    /** The viewer bridge and replay metadata are being prepared. */
    PREPARING,
    /** Required checkpoint or segment data is being loaded. */
    BUFFERING,
    /** The session is actively advancing its timeline. */
    PLAYING,
    /** The timeline is paused; this is not a zero-speed value. */
    PAUSED,
    /** The timeline reached the replay duration. */
    ENDED,
    /** The session was closed and cannot be used again. */
    CLOSED,
    /** The session stopped because its replay or playback operation failed. */
    FAILED
}
