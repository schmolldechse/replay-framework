package dev.voldechse.replayframework.core.playback.cache;

import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.SeekPoint;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe session-local buffer for decoded immutable packet frames. */
public final class MemoryPacketBuffer {

    private static final long FRAME_ACCOUNTING_BYTES = 64L;

    private final long maxBytes;
    private final Object lock = new Object();
    private final TreeMap<FrameKey, BufferedFrame> frames = new TreeMap<>(FrameKey.ORDER);
    private final Set<Integer> residentSegments = new HashSet<>();
    private final AtomicLong nextTieBreaker = new AtomicLong();
    private long accountedBytes;
    private long positionNanos;

    /** Creates an empty buffer with a positive logical memory budget. */
    public MemoryPacketBuffer(long maxBytes) {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    /** Adds a batch atomically and evicts the farthest frames when necessary. */
    public void addAll(Collection<RawPacketFrame> newFrames) {
        addBuffered(newFrames.stream()
                .map(frame -> new BufferedFrame(frame, -1, -1L))
                .toList());
    }

    /**
     * Adds one complete decoded segment together with its exact body locations.
     */
    public void addSegment(
            int segmentOrdinal,
            List<RawPacketFrame> newFrames,
            List<Long> frameOffsets) {
        if (segmentOrdinal < 0) {
            throw new IllegalArgumentException("segmentOrdinal must not be negative");
        }
        Objects.requireNonNull(newFrames, "newFrames");
        Objects.requireNonNull(frameOffsets, "frameOffsets");
        if (newFrames.size() != frameOffsets.size()) {
            throw new IllegalArgumentException(
                    "frameOffsets must contain one entry per frame");
        }
        List<BufferedFrame> located = new ArrayList<>(newFrames.size());
        for (int index = 0; index < newFrames.size(); index++) {
            located.add(new BufferedFrame(
                    newFrames.get(index), segmentOrdinal, frameOffsets.get(index)));
        }
        long segmentBytes = accountedBytes(located);
        if (segmentBytes > maxBytes) {
            throw new MemoryBufferCapacityException(
                    "decoded segment", maxBytes, segmentBytes);
        }

        synchronized (lock) {
            removeSegmentLocked(segmentOrdinal);
            addBufferedLocked(located);
            residentSegments.add(segmentOrdinal);
            evictToBudgetLocked(segmentOrdinal);
        }
    }

    private void addBuffered(Collection<BufferedFrame> newFrames) {
        Objects.requireNonNull(newFrames, "newFrames");
        List<BufferedFrame> immutableInput = List.copyOf(newFrames);
        for (BufferedFrame buffered : immutableInput) {
            long frameBytes = accountedBytes(buffered.frame());
            if (frameBytes > maxBytes) {
                throw new MemoryBufferCapacityException(
                        "decoded frame", maxBytes, frameBytes);
            }
        }

        synchronized (lock) {
            addBufferedLocked(immutableInput);
            evictToBudgetLocked(-1);
        }
    }

    private void addBufferedLocked(Collection<BufferedFrame> newFrames) {
        long batchBytes = accountedBytes(newFrames);
        long resultingBytes = addExact(accountedBytes, batchBytes);
        for (BufferedFrame buffered : newFrames) {
            RawPacketFrame frame = buffered.frame();
            FrameKey key = new FrameKey(
                    frame.elapsedNanos(),
                    frame.serverTick(),
                    frame.sequence(),
                    nextTieBreaker.getAndIncrement());
            frames.put(key, buffered);
        }
        accountedBytes = resultingBytes;
    }

    /** Returns an immutable, ordered half-open frame interval. */
    public List<RawPacketFrame> framesBetween(
            Duration startInclusive,
            Duration endExclusive) {
        long startNanos = toNanos(startInclusive, "startInclusive");
        long endNanos = toNanos(endExclusive, "endExclusive");
        if (startNanos < 0L || endNanos < startNanos) {
            throw new IllegalArgumentException("invalid frame interval");
        }
        synchronized (lock) {
            List<RawPacketFrame> result = new ArrayList<>();
            for (var entry : frames.entrySet()) {
                long elapsedNanos = entry.getKey().elapsedNanos();
                if (elapsedNanos >= startNanos && elapsedNanos < endNanos) {
                    result.add(entry.getValue().frame());
                }
            }
            return List.copyOf(result);
        }
    }

    /**
     * Returns frames at or after an exclusive checkpoint body offset and
     * before the requested end time.
     */
    public List<RawPacketFrame> framesAfter(
            SeekPoint checkpoint,
            Duration endExclusive) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        long endNanos = toNanos(endExclusive, "endExclusive");
        if (endNanos < checkpoint.elapsedNanos()) {
            throw new IllegalArgumentException("endExclusive precedes checkpoint");
        }
        synchronized (lock) {
            List<RawPacketFrame> result = new ArrayList<>();
            for (var entry : frames.entrySet()) {
                BufferedFrame buffered = entry.getValue();
                RawPacketFrame frame = buffered.frame();
                if (frame.elapsedNanos() >= endNanos
                        || buffered.segmentOrdinal() < checkpoint.segmentOrdinal()) {
                    continue;
                }
                if (buffered.segmentOrdinal() == checkpoint.segmentOrdinal()
                        && buffered.frameOffset() < checkpoint.frameOffset()) {
                    continue;
                }
                result.add(frame);
            }
            return List.copyOf(result);
        }
    }

    /** Retains only the requested window and then enforces the memory budget. */
    public void retain(Duration position, Duration behind, Duration ahead) {
        retain(position, behind, ahead, null);
    }

    /**
     * Retains complete located segments selected by their owner while applying
     * the time window to frames without a segment location.
     *
     * @return immutable ordinals of all complete segments that remain resident
     */
    public Set<Integer> retainSegments(
            Duration position,
            Duration behind,
            Duration ahead,
            Collection<Integer> retainedSegmentOrdinals) {
        Objects.requireNonNull(retainedSegmentOrdinals, "retainedSegmentOrdinals");
        Set<Integer> retained = Set.copyOf(retainedSegmentOrdinals);
        for (int ordinal : retained) {
            if (ordinal < 0) {
                throw new IllegalArgumentException(
                        "retained segment ordinal must not be negative");
            }
        }
        return retain(position, behind, ahead, retained);
    }

    private Set<Integer> retain(
            Duration position,
            Duration behind,
            Duration ahead,
            Set<Integer> retainedSegmentOrdinals) {
        long positionValue = toNanos(position, "position");
        long behindValue = toNanos(behind, "behind");
        long aheadValue = toNanos(ahead, "ahead");
        if (positionValue < 0L || behindValue < 0L || aheadValue < 0L) {
            throw new IllegalArgumentException("retention values must not be negative");
        }
        long lowerBound = positionValue > behindValue
                ? positionValue - behindValue
                : 0L;
        long upperBound = saturatingAdd(positionValue, aheadValue);

        synchronized (lock) {
            positionNanos = positionValue;
            Set<Integer> retained = retainedSegmentOrdinals;
            if (retained == null) {
                retained = new HashSet<>();
                for (var entry : frames.entrySet()) {
                    BufferedFrame buffered = entry.getValue();
                    long elapsedNanos = entry.getKey().elapsedNanos();
                    if (buffered.segmentOrdinal() >= 0
                            && elapsedNanos >= lowerBound
                            && elapsedNanos <= upperBound) {
                        retained.add(buffered.segmentOrdinal());
                    }
                }
            }
            for (int ordinal : List.copyOf(residentSegments)) {
                if (!retained.contains(ordinal)) {
                    removeSegmentLocked(ordinal);
                }
            }
            frames.entrySet().removeIf(entry -> {
                if (entry.getValue().segmentOrdinal() >= 0) {
                    return false;
                }
                long elapsedNanos = entry.getKey().elapsedNanos();
                if (elapsedNanos < lowerBound || elapsedNanos > upperBound) {
                    accountedBytes = subtractExact(
                            accountedBytes,
                            accountedBytes(entry.getValue().frame()));
                    return true;
                }
                return false;
            });
            evictToBudgetLocked(-1);
            return Set.copyOf(residentSegments);
        }
    }

    /** Returns the ordinals whose complete decoded frames remain resident. */
    public Set<Integer> residentSegmentOrdinals() {
        synchronized (lock) {
            return Set.copyOf(residentSegments);
        }
    }

    /** Returns the current immutable time window and logical byte usage. */
    public BufferWindow window() {
        synchronized (lock) {
            if (frames.isEmpty()) {
                return new BufferWindow(Optional.empty(), Optional.empty(), accountedBytes);
            }
            return new BufferWindow(
                    Optional.of(Duration.ofNanos(frames.firstKey().elapsedNanos())),
                    Optional.of(Duration.ofNanos(frames.lastKey().elapsedNanos())),
                    accountedBytes);
        }
    }

    /** Returns the logical decoded payload and record bytes currently held. */
    public long accountedBytes() {
        synchronized (lock) {
            return accountedBytes;
        }
    }

    /** Removes all frames and resets the logical byte counter. */
    public void clear() {
        synchronized (lock) {
            frames.clear();
            residentSegments.clear();
            accountedBytes = 0L;
        }
    }

    private void evictToBudgetLocked(int protectedSegmentOrdinal) {
        while (accountedBytes > maxBytes && !frames.isEmpty()) {
            FrameKey victim = frames.entrySet().stream()
                    .filter(entry -> protectedSegmentOrdinal < 0
                            || entry.getValue().segmentOrdinal()
                            != protectedSegmentOrdinal)
                    .map(Map.Entry::getKey)
                    .max(this::compareEvictionPriority)
                    .orElseThrow(() -> new MemoryBufferCapacityException(
                            "decoded segment", maxBytes, accountedBytes));
            BufferedFrame buffered = frames.get(victim);
            if (buffered.segmentOrdinal() >= 0) {
                removeSegmentLocked(buffered.segmentOrdinal());
            } else {
                removeFrameLocked(victim);
            }
        }
    }

    private void removeSegmentLocked(int segmentOrdinal) {
        frames.entrySet().removeIf(entry -> {
            if (entry.getValue().segmentOrdinal() != segmentOrdinal) {
                return false;
            }
            accountedBytes = subtractExact(
                    accountedBytes, accountedBytes(entry.getValue().frame()));
            return true;
        });
        residentSegments.remove(segmentOrdinal);
    }

    private void removeFrameLocked(FrameKey key) {
        BufferedFrame removed = frames.remove(key);
        if (removed != null) {
            accountedBytes = subtractExact(
                    accountedBytes, accountedBytes(removed.frame()));
        }
    }

    private int compareEvictionPriority(FrameKey left, FrameKey right) {
        long leftDistance = distance(left.elapsedNanos(), positionNanos);
        long rightDistance = distance(right.elapsedNanos(), positionNanos);
        int result = Long.compare(leftDistance, rightDistance);
        if (result != 0) {
            return result;
        }
        boolean leftBehind = left.elapsedNanos() < positionNanos;
        boolean rightBehind = right.elapsedNanos() < positionNanos;
        if (leftBehind != rightBehind) {
            return leftBehind ? 1 : -1;
        }
        return FrameKey.ORDER.compare(left, right);
    }

    private static long accountedBytes(RawPacketFrame frame) {
        Objects.requireNonNull(frame, "frame");
        return addExact(FRAME_ACCOUNTING_BYTES, frame.payload().length);
    }

    private static long accountedBytes(Collection<BufferedFrame> frames) {
        long total = 0L;
        for (BufferedFrame buffered : frames) {
            total = addExact(total, accountedBytes(buffered.frame()));
        }
        return total;
    }

    private static long toNanos(Duration value, String name) {
        Objects.requireNonNull(value, name);
        try {
            return value.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " exceeds nanosecond range", exception);
        }
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long distance(long left, long right) {
        if (left >= right) {
            return left - right;
        }
        return right - left;
    }

    private static long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new MemoryBufferCapacityException(Long.MAX_VALUE, right, exception);
        }
    }

    private static long subtractExact(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("memory buffer byte counter underflow", exception);
        }
    }

    /** Immutable description of the currently retained frame interval. */
    public record BufferWindow(
            Optional<Duration> earliest,
            Optional<Duration> latest,
            long accountedBytes) {

        /** Validates the immutable window snapshot. */
        public BufferWindow {
            Objects.requireNonNull(earliest, "earliest");
            Objects.requireNonNull(latest, "latest");
            if (accountedBytes < 0L) {
                throw new IllegalArgumentException("accountedBytes must not be negative");
            }
            if (earliest.isEmpty() != latest.isEmpty()) {
                throw new IllegalArgumentException("window endpoints must be both present or empty");
            }
            if (earliest.isPresent()
                    && earliest.get().compareTo(latest.orElseThrow()) > 0) {
                throw new IllegalArgumentException("earliest must not exceed latest");
            }
        }
    }

    /** Signals that one decoded frame cannot fit the configured memory budget. */
    public static final class MemoryBufferCapacityException extends IllegalStateException {
        private MemoryBufferCapacityException(
                String subject,
                long maxBytes,
                long requestedBytes) {
            super(subject + " exceeds memory budget: max=" + maxBytes
                    + ", requested=" + requestedBytes);
        }

        private MemoryBufferCapacityException(long maxBytes, long requestedBytes, Throwable cause) {
            super("memory buffer byte accounting overflow: max=" + maxBytes
                    + ", requested=" + requestedBytes, cause);
        }
    }

    private record FrameKey(
            long elapsedNanos,
            long serverTick,
            int sequence,
            long tieBreaker) {

        private static final Comparator<FrameKey> ORDER = Comparator
                .comparingLong(FrameKey::elapsedNanos)
                .thenComparingLong(FrameKey::serverTick)
                .thenComparingInt(FrameKey::sequence)
                .thenComparingLong(FrameKey::tieBreaker);

        private FrameKey {
            if (elapsedNanos < 0L || serverTick < 0L || sequence < 0 || tieBreaker < 0L) {
                throw new IllegalArgumentException("frame key values must not be negative");
            }
        }
    }

    private record BufferedFrame(
            RawPacketFrame frame,
            int segmentOrdinal,
            long frameOffset) {

        private BufferedFrame {
            Objects.requireNonNull(frame, "frame");
            if ((segmentOrdinal < 0) != (frameOffset < 0L)) {
                throw new IllegalArgumentException(
                        "segment ordinal and frame offset must either both be known or unknown");
            }
        }
    }
}
