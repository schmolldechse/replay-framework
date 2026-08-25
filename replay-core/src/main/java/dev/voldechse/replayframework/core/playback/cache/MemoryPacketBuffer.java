package dev.voldechse.replayframework.core.playback.cache;

import dev.voldechse.replayframework.format.RawPacketFrame;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe session-local buffer for decoded immutable packet frames. */
public final class MemoryPacketBuffer {

    private static final long FRAME_ACCOUNTING_BYTES = 64L;

    private final long maxBytes;
    private final Object lock = new Object();
    private final TreeMap<FrameKey, RawPacketFrame> frames = new TreeMap<>(FrameKey.ORDER);
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
        Objects.requireNonNull(newFrames, "newFrames");
        List<RawPacketFrame> immutableInput = List.copyOf(newFrames);
        long batchBytes = 0L;
        for (RawPacketFrame frame : immutableInput) {
            long frameBytes = accountedBytes(frame);
            if (frameBytes > maxBytes) {
                throw new MemoryBufferCapacityException(maxBytes, frameBytes);
            }
            batchBytes = addExact(batchBytes, frameBytes);
        }

        synchronized (lock) {
            long resultingBytes = addExact(accountedBytes, batchBytes);
            if (resultingBytes < 0L) {
                throw new MemoryBufferCapacityException(maxBytes, resultingBytes);
            }
            for (RawPacketFrame frame : immutableInput) {
                FrameKey key = new FrameKey(
                        frame.elapsedNanos(),
                        frame.serverTick(),
                        frame.sequence(),
                        nextTieBreaker.getAndIncrement());
                frames.put(key, frame);
            }
            accountedBytes = resultingBytes;
            evictToBudgetLocked();
        }
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
                    result.add(entry.getValue());
                }
            }
            return List.copyOf(result);
        }
    }

    /** Retains only the requested window and then enforces the memory budget. */
    public void retain(Duration position, Duration behind, Duration ahead) {
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
            frames.entrySet().removeIf(entry -> {
                long elapsedNanos = entry.getKey().elapsedNanos();
                if (elapsedNanos < lowerBound || elapsedNanos > upperBound) {
                    accountedBytes = subtractExact(
                            accountedBytes,
                            accountedBytes(entry.getValue()));
                    return true;
                }
                return false;
            });
            evictToBudgetLocked();
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
            accountedBytes = 0L;
        }
    }

    private void evictToBudgetLocked() {
        while (accountedBytes > maxBytes && !frames.isEmpty()) {
            FrameKey victim = frames.keySet().stream()
                    .max(this::compareEvictionPriority)
                    .orElseThrow();
            RawPacketFrame removed = frames.remove(victim);
            accountedBytes = subtractExact(accountedBytes, accountedBytes(removed));
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
        private MemoryBufferCapacityException(long maxBytes, long requestedBytes) {
            super("decoded frame exceeds memory budget: max=" + maxBytes
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
}
