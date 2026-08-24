package dev.voldechse.replayframework.format;

/**
 * Immutable location used by the replay time index.
 *
 * @param elapsedNanos replay time in nanoseconds
 * @param checkpointOrdinal checkpoint to apply before deltas
 * @param segmentOrdinal segment containing the delta position
 * @param frameOffset offset in the uncompressed segment body
 */
public record SeekPoint(
        long elapsedNanos,
        int checkpointOrdinal,
        int segmentOrdinal,
        long frameOffset) {

    /** Validates the nonnegative wire coordinates. */
    public SeekPoint {
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("elapsedNanos must not be negative");
        }
        if (checkpointOrdinal < 0) {
            throw new IllegalArgumentException("checkpointOrdinal must not be negative");
        }
        if (segmentOrdinal < 0) {
            throw new IllegalArgumentException("segmentOrdinal must not be negative");
        }
        if (frameOffset < 0L) {
            throw new IllegalArgumentException("frameOffset must not be negative");
        }
    }
}
