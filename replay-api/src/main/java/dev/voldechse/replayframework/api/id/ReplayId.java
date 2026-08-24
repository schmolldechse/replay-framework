package dev.voldechse.replayframework.api.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable identifier of a replay.
 *
 * @param value UUID value of the replay
 */
public record ReplayId(UUID value) {

    /** Ensures that the identifier always contains a UUID value. */
    public ReplayId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Creates a new randomly generated replay identifier.
     *
     * @return a new replay identifier
     */
    public static ReplayId random() {
        return new ReplayId(UUID.randomUUID());
    }

    /**
     * Parses a UUID string into a replay identifier.
     *
     * @param value UUID string
     * @return parsed replay identifier
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not a UUID
     */
    public static ReplayId parse(String value) {
        Objects.requireNonNull(value, "value");
        return new ReplayId(UUID.fromString(value));
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
