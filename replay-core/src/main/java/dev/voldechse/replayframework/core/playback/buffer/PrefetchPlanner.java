package dev.voldechse.replayframework.core.playback.buffer;

import dev.voldechse.replayframework.api.playback.PlaybackBufferOptions;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.core.playback.cache.SegmentCache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure, deterministic selection of segments for a playback request. */
public final class PrefetchPlanner {

    /** Playback movement used to prioritize the planned segment window. */
    public enum Direction {
        /** Select the current segment and the following timeline window. */
        FORWARD,
        /** Select the current segment and the preceding timeline window. */
        BACKWARD,
        /** Select the target segment and expand around the seek position. */
        SEEK
    }

    /** Immutable result of one deterministic prefetch calculation. */
    public record Plan(
            List<SegmentCache.SegmentRef> segments,
            long startNanos,
            long endNanos,
            long plannedBytes,
            boolean minimumResumeSatisfied) {

        /** Validates and freezes the planned segment list. */
        public Plan {
            Objects.requireNonNull(segments, "segments");
            segments = List.copyOf(segments);
            if (startNanos < 0L || endNanos < startNanos) {
                throw new IllegalArgumentException("plan time bounds are invalid");
            }
            if (plannedBytes < 0L) {
                throw new IllegalArgumentException("plannedBytes must not be negative");
            }
        }
    }

    /**
     * Calculates a direction-aware, disk-budgeted plan.
     *
     * @param position current replay position
     * @param replayDuration complete replay duration
     * @param speed requested playback speed
     * @param direction requested movement direction
     * @param inputSegments ordered or unordered segment references
     * @param options session buffer options
     * @return immutable prefetch plan
     */
    public Plan plan(
            Duration position,
            Duration replayDuration,
            PlaybackSpeed speed,
            Direction direction,
            List<SegmentCache.SegmentRef> inputSegments,
            PlaybackBufferOptions options) {
        long positionNanos = toNanos(position, "position");
        long durationNanos = toNanos(replayDuration, "replayDuration");
        if (positionNanos < 0L || durationNanos < 0L || positionNanos > durationNanos) {
            throw new IllegalArgumentException("position must be within replay duration");
        }
        Objects.requireNonNull(speed, "speed");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(inputSegments, "inputSegments");
        Objects.requireNonNull(options, "options");

        List<SegmentCache.SegmentRef> segments = inputSegments.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparingLong(SegmentCache.SegmentRef::startNanos)
                        .thenComparingLong(SegmentCache.SegmentRef::endNanos)
                        .thenComparingInt(SegmentCache.SegmentRef::ordinal))
                .toList();
        validateOrdinals(segments);

        SegmentCache.SegmentRef target = targetSegment(positionNanos, segments, direction);
        long scaledAhead = Math.min(
                Math.max(0L, durationNanos - positionNanos),
                scaledPreload(options.preloadAhead(), speed));
        long retention = Math.min(positionNanos, toNanos(options.retainBehind(), "retainBehind"));
        long minimum = Math.min(
                Math.max(0L, direction == Direction.BACKWARD
                        ? positionNanos
                        : durationNanos - positionNanos),
                toNanos(options.minimumResumeBuffer(), "minimumResumeBuffer"));

        long lowerBound;
        long upperBound;
        switch (direction) {
            case FORWARD -> {
                lowerBound = positionNanos;
                upperBound = saturatingAdd(positionNanos, scaledAhead);
            }
            case BACKWARD -> {
                lowerBound = positionNanos - retention;
                upperBound = positionNanos;
            }
            case SEEK -> {
                long seekBehind = Math.min(positionNanos, toNanos(options.retainBehind(), "retainBehind"));
                lowerBound = positionNanos - seekBehind;
                upperBound = saturatingAdd(positionNanos, scaledAhead);
            }
            default -> throw new IllegalStateException("unhandled direction: " + direction);
        }

        List<SegmentCache.SegmentRef> candidates = segments.stream()
                .filter(segment -> overlaps(segment, lowerBound, upperBound))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        if (target != null && !candidates.contains(target)) {
            candidates.add(target);
        }
        candidates.sort(priorityComparator(positionNanos, direction, target));

        List<SegmentCache.SegmentRef> selected = selectWithinBudget(
                candidates,
                target,
                options.diskBudgetBytes());
        selected.sort(Comparator
                .comparingLong(SegmentCache.SegmentRef::startNanos)
                .thenComparingLong(SegmentCache.SegmentRef::endNanos)
                .thenComparingInt(SegmentCache.SegmentRef::ordinal));

        long plannedBytes = 0L;
        long selectedStart = positionNanos;
        long selectedEnd = positionNanos;
        for (SegmentCache.SegmentRef segment : selected) {
            plannedBytes = addExact(plannedBytes, segment.artifact().sizeBytes());
            selectedStart = Math.min(selectedStart, segment.startNanos());
            selectedEnd = Math.max(selectedEnd, segment.endNanos());
        }

        boolean minimumSatisfied = switch (direction) {
            case FORWARD, SEEK -> selectedEnd >= saturatingAdd(positionNanos, minimum);
            case BACKWARD -> selectedStart <= positionNanos - minimum;
        };
        return new Plan(selected, selectedStart, selectedEnd, plannedBytes, minimumSatisfied);
    }

    private static List<SegmentCache.SegmentRef> selectWithinBudget(
            List<SegmentCache.SegmentRef> candidates,
            SegmentCache.SegmentRef target,
            long diskBudgetBytes) {
        List<SegmentCache.SegmentRef> selected = new ArrayList<>();
        Set<Integer> selectedOrdinals = new HashSet<>();
        long usedBytes = 0L;
        if (target != null) {
            selected.add(target);
            selectedOrdinals.add(target.ordinal());
            usedBytes = target.artifact().sizeBytes();
        }
        for (SegmentCache.SegmentRef candidate : candidates) {
            if (!selectedOrdinals.add(candidate.ordinal())) {
                continue;
            }
            long candidateBytes = candidate.artifact().sizeBytes();
            if (candidateBytes > diskBudgetBytes - Math.min(diskBudgetBytes, usedBytes)) {
                continue;
            }
            usedBytes = addExact(usedBytes, candidateBytes);
            selected.add(candidate);
        }
        return selected;
    }

    private static Comparator<SegmentCache.SegmentRef> priorityComparator(
            long positionNanos,
            Direction direction,
            SegmentCache.SegmentRef target) {
        return Comparator
                .comparingInt((SegmentCache.SegmentRef segment) -> segment.equals(target) ? 0 : 1)
                .thenComparingLong(segment -> direction == Direction.BACKWARD
                        ? Math.max(0L, positionNanos - segment.endNanos())
                        : Math.max(0L, segment.startNanos() - positionNanos))
                .thenComparingLong(SegmentCache.SegmentRef::startNanos)
                .thenComparingInt(SegmentCache.SegmentRef::ordinal);
    }

    private static SegmentCache.SegmentRef targetSegment(
            long positionNanos,
            List<SegmentCache.SegmentRef> segments,
            Direction direction) {
        for (SegmentCache.SegmentRef segment : segments) {
            if (positionNanos >= segment.startNanos() && positionNanos <= segment.endNanos()) {
                return segment;
            }
        }
        if (segments.isEmpty()) {
            return null;
        }
        return switch (direction) {
            case BACKWARD -> segments.stream()
                    .filter(segment -> segment.endNanos() <= positionNanos)
                    .max(Comparator.comparingLong(SegmentCache.SegmentRef::endNanos))
                    .orElse(segments.getFirst());
            case FORWARD, SEEK -> segments.stream()
                    .filter(segment -> segment.startNanos() >= positionNanos)
                    .findFirst()
                    .orElse(segments.getLast());
        };
    }

    private static boolean overlaps(SegmentCache.SegmentRef segment, long lowerBound, long upperBound) {
        return segment.endNanos() >= lowerBound && segment.startNanos() <= upperBound;
    }

    private static void validateOrdinals(List<SegmentCache.SegmentRef> segments) {
        Set<Integer> ordinals = new HashSet<>();
        for (SegmentCache.SegmentRef segment : segments) {
            if (!ordinals.add(segment.ordinal())) {
                throw new IllegalArgumentException("duplicate segment ordinal: " + segment.ordinal());
            }
        }
    }

    private static long scaledPreload(Duration preload, PlaybackSpeed speed) {
        long nanos = toNanos(preload, "preloadAhead");
        return switch (speed) {
            case QUARTER -> divideCeiling(nanos, 4L);
            case HALF -> divideCeiling(nanos, 2L);
            case NORMAL -> nanos;
            case DOUBLE -> saturatingMultiply(nanos, 2L);
            case QUADRUPLE -> saturatingMultiply(nanos, 4L);
        };
    }

    private static long divideCeiling(long value, long divisor) {
        long quotient = value / divisor;
        return value % divisor == 0L ? quotient : quotient + 1L;
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("planned disk bytes exceed long range", exception);
        }
    }

    private static long toNanos(Duration value, String name) {
        Objects.requireNonNull(value, name);
        try {
            return value.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " exceeds nanosecond range", exception);
        }
    }
}
