package dev.voldechse.replayframework.api.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable identifier of a recording session.
 *
 * @param value UUID value of the recording session
 */
public record RecordingSessionId(UUID value) {

    /** Ensures that the identifier always contains a UUID value. */
    public RecordingSessionId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Creates a new randomly generated recording-session identifier.
     *
     * @return a new recording-session identifier
     */
    public static RecordingSessionId random() {
        return new RecordingSessionId(UUID.randomUUID());
    }

    /**
     * Parses a UUID string into a recording-session identifier.
     *
     * @param value UUID string
     * @return parsed recording-session identifier
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not a UUID
     */
    public static RecordingSessionId parse(String value) {
        Objects.requireNonNull(value, "value");
        return new RecordingSessionId(UUID.fromString(value));
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
