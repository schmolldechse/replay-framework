package dev.voldechse.replayframework.api.recording;

import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Public handle for one recording lifecycle.
 *
 * <p>Stopping is the only recording mutation exposed to integrators. Capture
 * queues, writers, segment rotation and finalization remain internal.</p>
 */
public interface RecordingSession {
    /**
     * Returns this recording session's identifier.
     *
     * @return recording session identifier
     */
    RecordingSessionId id();

    /**
     * Returns the replay catalog identifier produced by this recording.
     *
     * @return replay identifier
     */
    ReplayId replayId();

    /**
     * Returns the current lifecycle status.
     *
     * @return recording status
     */
    RecordingStatus status();

    /**
     * Returns an immutable point-in-time metrics snapshot.
     *
     * @return metrics snapshot
     */
    Metrics metrics();

    /**
     * Returns the stable failure classification, when the session failed.
     *
     * @return failure code, or empty when none is available
     */
    Optional<ReplayFailureCode> failureCode();

    /**
     * Returns a safe diagnostic description, when the session failed.
     *
     * @return failure description, or empty when none is available
     */
    Optional<String> failureDescription();

    /**
     * Requests manual finalization. Repeated calls share the same lifecycle
     * result and never start a second finalizer.
     *
     * @return stage completed with the terminal session view
     */
    CompletionStage<RecordingSession> stop();

    /**
     * Immutable recording progress metrics.
     *
     * @param duration elapsed recording duration
     * @param totalBytes total uncompressed captured bytes
     * @param packetCount captured packet count
     * @param segmentCount completed or active segment count
     * @param checkpointCount checkpoint count
     */
    record Metrics(
            Duration duration,
            long totalBytes,
            long packetCount,
            long segmentCount,
            long checkpointCount) {
        /** Validates that a metrics snapshot cannot contain negative values. */
        public Metrics {
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative()) {
                throw new IllegalArgumentException("duration must not be negative");
            }
            if (totalBytes < 0 || packetCount < 0 || segmentCount < 0 || checkpointCount < 0) {
                throw new IllegalArgumentException("metrics counters must not be negative");
            }
        }
    }
}
