package dev.voldechse.replayframework.api.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable identifier of a playback session.
 *
 * @param value UUID value of the playback session
 */
public record PlaybackSessionId(UUID value) {

    /** Ensures that the identifier always contains a UUID value. */
    public PlaybackSessionId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Creates a new randomly generated playback-session identifier.
     *
     * @return a new playback-session identifier
     */
    public static PlaybackSessionId random() {
        return new PlaybackSessionId(UUID.randomUUID());
    }

    /**
     * Parses a UUID string into a playback-session identifier.
     *
     * @param value UUID string
     * @return parsed playback-session identifier
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not a UUID
     */
    public static PlaybackSessionId parse(String value) {
        Objects.requireNonNull(value, "value");
        return new PlaybackSessionId(UUID.fromString(value));
    }

    /**
     * Returns the canonical UUID string.
     *
     * @return canonical UUID string
     */
    @Override
    public String toString() {
        return value.toString();
    }
}
