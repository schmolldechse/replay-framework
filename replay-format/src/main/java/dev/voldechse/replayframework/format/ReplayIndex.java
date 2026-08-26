package dev.voldechse.replayframework.format;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, ordered time index for checkpoint- and segment-based seeking. */
public final class ReplayIndex {

    private final List<SeekPoint> points;

    private ReplayIndex(List<SeekPoint> points) {
        Objects.requireNonNull(points, "points");
        for (SeekPoint point : points) {
            Objects.requireNonNull(point, "points contains null");
        }
        for (int index = 1; index < points.size(); index++) {
            if (compare(points.get(index - 1), points.get(index)) > 0) {
                throw new IllegalArgumentException(
                        "points must be ordered by time, checkpoint, segment and frame offset");
            }
        }
        this.points = List.copyOf(points);
    }

    /**
     * Creates an immutable index from ordered seek points.
     *
     * @param points ordered seek points
     * @return immutable replay index
     */
    public static ReplayIndex of(List<SeekPoint> points) {
        return new ReplayIndex(points);
    }

    /**
     * Returns the immutable seek point list.
     *
     * @return ordered seek points
     */
    public List<SeekPoint> points() {
        return points;
    }

    /**
     * Finds the last point whose replay time is not after the target.
     *
     * @param target nonnegative replay target
     * @return floor point, or empty before the first point
     */
    public Optional<SeekPoint> seekFloor(Duration target) {
        Objects.requireNonNull(target, "target");
        if (target.isNegative()) {
            throw new IllegalArgumentException("target must not be negative");
        }
        final long targetNanos;
        try {
            targetNanos = target.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("target duration exceeds nanosecond range", exception);
        }

        int low = 0;
        int high = points.size() - 1;
        int result = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (points.get(middle).elapsedNanos() <= targetNanos) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result < 0 ? Optional.empty() : Optional.of(points.get(result));
    }

    /**
     * Finds the checkpoint anchor for the last indexed point at or before the
     * target. A checkpoint ordinal is reused by all delta frames until the
     * next checkpoint, so the time-floor point itself is not necessarily the
     * point at which the checkpoint artifact starts.
     *
     * @param target nonnegative replay target
     * @return checkpoint anchor, or empty before the first point
     */
    public Optional<SeekPoint> seekCheckpointFloor(Duration target) {
        SeekPoint floor = seekFloor(target).orElse(null);
        if (floor == null) {
            return Optional.empty();
        }
        int checkpointOrdinal = floor.checkpointOrdinal();
        for (SeekPoint point : points) {
            if (point.checkpointOrdinal() == checkpointOrdinal) {
                return Optional.of(point);
            }
        }
        return Optional.empty();
    }

    private static int compare(SeekPoint left, SeekPoint right) {
        int result = Long.compare(left.elapsedNanos(), right.elapsedNanos());
        if (result != 0) {
            return result;
        }
        result = Integer.compare(left.checkpointOrdinal(), right.checkpointOrdinal());
        if (result != 0) {
            return result;
        }
        result = Integer.compare(left.segmentOrdinal(), right.segmentOrdinal());
        if (result != 0) {
            return result;
        }
        return Long.compare(left.frameOffset(), right.frameOffset());
    }
}
