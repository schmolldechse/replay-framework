package dev.voldechse.replayframework.api.playback;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable point-in-time view of one playback timeline.
 *
 * @param position current timeline position
 * @param duration replay duration
 * @param speed current playback speed
 * @param status current session status
 * @param bufferedBehind retained duration behind the position
 * @param bufferedAhead available duration ahead of the position
 */
public record PlaybackSnapshot(
        Duration position,
        Duration duration,
        PlaybackSpeed speed,
        PlaybackStatus status,
        Duration bufferedBehind,
        Duration bufferedAhead) {

    /** Validates timeline and buffer invariants. */
    public PlaybackSnapshot {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(speed, "speed");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(bufferedBehind, "bufferedBehind");
        Objects.requireNonNull(bufferedAhead, "bufferedAhead");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        if (position.isNegative() || position.compareTo(duration) > 0) {
            throw new IllegalArgumentException("position must be within duration");
        }
        if (bufferedBehind.isNegative() || bufferedAhead.isNegative()) {
            throw new IllegalArgumentException("buffer durations must not be negative");
        }
    }
}
