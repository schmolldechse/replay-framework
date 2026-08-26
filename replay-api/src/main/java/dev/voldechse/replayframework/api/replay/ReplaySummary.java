package dev.voldechse.replayframework.api.replay;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Read-only catalog projection used when a replay list needs fixed replay fields.
 *
 * @param replayId replay identifier
 * @param title replay title
 * @param description replay description
 * @param status current catalog status
 * @param adapterId version adapter identifier
 * @param duration recorded replay duration
 * @param createdAt catalog creation timestamp
 * @param totalBytes total published replay bytes
 */
public record ReplaySummary(
        ReplayId replayId,
        String title,
        String description,
        RecordingStatus status,
        String adapterId,
        Duration duration,
        Instant createdAt,
        long totalBytes) {

    /** Validates the immutable values exposed to public consumers. */
    public ReplaySummary {
        Objects.requireNonNull(replayId, "replayId");
        requireNonBlank(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(status, "status");
        requireNonBlank(adapterId, "adapterId");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        if (totalBytes < 0) {
            throw new IllegalArgumentException("totalBytes must not be negative");
        }
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
