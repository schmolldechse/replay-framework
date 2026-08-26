package dev.voldechse.replayframework.core.playback.buffer;

import dev.voldechse.replayframework.api.playback.PlaybackBufferOptions;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactReader;
import dev.voldechse.replayframework.core.playback.cache.CacheEntryLease;
import dev.voldechse.replayframework.core.playback.cache.MemoryPacketBuffer;
import dev.voldechse.replayframework.core.playback.cache.SegmentCache;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplaySegmentReader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Session-local asynchronous facade over segment cache, decode and memory retention. */
public final class PlaybackBuffer implements AutoCloseable {

    private final ReplayArtifactReader.VerifiedReplay replay;
    private final List<SegmentCache.SegmentRef> segments;
    private final SegmentCache segmentCache;
    private final ReplaySegmentReader segmentReader;
    private final PlaybackBufferOptions options;
    private final Executor ioExecutor;
    private final MemoryPacketBuffer memoryBuffer;
    private final PrefetchPlanner planner = new PrefetchPlanner();
    private final Object stateLock = new Object();
    private final Map<Integer, CompletionStage<Void>> loadingSegments = new HashMap<>();
    private final Set<Integer> loadedSegments = new HashSet<>();
    private final Map<Integer, Long> loadedDiskBytes = new HashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private boolean closed;

    /**
     * Creates one independent decoded buffer for a verified replay.
     *
     * @param replay verified replay manifest
     * @param segments ordered segment references
     * @param artifactReader retained for the cache boundary
     * @param segmentCache shared verified segment cache
     * @param segmentReader strict segment decoder
     * @param options session buffer options
     * @param ioExecutor executor for fetch, file and decode work
     */
    public PlaybackBuffer(
            ReplayArtifactReader.VerifiedReplay replay,
            List<SegmentCache.SegmentRef> segments,
            ReplayArtifactReader artifactReader,
            SegmentCache segmentCache,
            ReplaySegmentReader segmentReader,
            PlaybackBufferOptions options,
            Executor ioExecutor) {
        this.replay = Objects.requireNonNull(replay, "replay");
        Objects.requireNonNull(artifactReader, "artifactReader");
        this.segments = validateSegments(segments);
        this.segmentCache = Objects.requireNonNull(segmentCache, "segmentCache");
        this.segmentReader = Objects.requireNonNull(segmentReader, "segmentReader");
        this.options = Objects.requireNonNull(options, "options");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.memoryBuffer = new MemoryPacketBuffer(options.memoryBudgetBytes());
    }

    /**
     * Ensures the requested position has a direction-appropriate buffer window.
     *
     * @param position requested timeline position
     * @param speed requested playback speed
     * @param direction movement direction
     * @return completion carrying current buffer progress
     */
    public CompletionStage<BufferProgress> ensureAvailable(
            Duration position,
            PlaybackSpeed speed,
            PrefetchPlanner.Direction direction) {
        try {
            long positionNanos = validatePosition(position);
            Objects.requireNonNull(speed, "speed");
            Objects.requireNonNull(direction, "direction");
            ensureOpen();
            PrefetchPlanner.Plan plan = planner.plan(
                    position,
                    Duration.ofNanos(replay.manifest().durationNanos()),
                    speed,
                    direction,
                    segments,
                    options);
            long requestGeneration = generation.get();
            return loadSegments(plan.segments(), requestGeneration)
                    .thenApplyAsync(ignored -> {
                        ensureGeneration(requestGeneration);
                        memoryBuffer.retain(
                                position,
                                options.retainBehind(),
                                Duration.ofNanos(scaledPreload(options.preloadAhead(), speed)));
                        forgetOutsideRetainedWindow(position, speed);
                        return progress(Duration.ofNanos(positionNanos));
                    }, ioExecutor);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /**
     * Returns all decoded frames in the half-open interval, loading overlapping segments first.
     *
     * @param startInclusive inclusive frame time
     * @param endExclusive exclusive frame time
     * @return immutable ordered frames
     */
    public CompletionStage<List<RawPacketFrame>> framesBetween(
            Duration startInclusive,
            Duration endExclusive) {
        try {
            long startNanos = toNanos(startInclusive, "startInclusive");
            long endNanos = toNanos(endExclusive, "endExclusive");
            long durationNanos = replay.manifest().durationNanos();
            if (startNanos < 0L || endNanos < startNanos || endNanos > durationNanos) {
                throw new IllegalArgumentException("frame interval is outside replay duration");
            }
            ensureOpen();
            if (startNanos == endNanos) {
                return CompletableFuture.completedFuture(List.of());
            }
            List<SegmentCache.SegmentRef> required = segments.stream()
                    .filter(segment -> segment.endNanos() >= startNanos
                            && segment.startNanos() < endNanos)
                    .toList();
            if (areLoaded(required)) {
                return CompletableFuture.completedFuture(
                        memoryBuffer.framesBetween(startInclusive, endExclusive));
            }
            long requestGeneration = generation.get();
            return loadSegments(required, requestGeneration)
                    .thenApplyAsync(ignored -> {
                        ensureGeneration(requestGeneration);
                        return memoryBuffer.framesBetween(startInclusive, endExclusive);
                    }, ioExecutor);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /** Returns progress relative to a session timeline position. */
    public BufferProgress progress(Duration position) {
        long positionNanos = validatePosition(position);
        MemoryPacketBuffer.BufferWindow window = memoryBuffer.window();
        LoadedCoverage coverage = loadedCoverage(positionNanos);
        long behindNanos = positionNanos - coverage.startNanos();
        long aheadNanos = coverage.endNanos() - positionNanos;
        long diskBytes;
        synchronized (stateLock) {
            diskBytes = loadedDiskBytes.values().stream()
                    .mapToLong(Long::longValue)
                    .sum();
        }
        Duration minimum = options.minimumResumeBuffer();
        long minimumNanos = toNanos(minimum, "minimumResumeBuffer");
        long remainingNanos = replay.manifest().durationNanos() - positionNanos;
        long requiredAheadNanos = Math.min(minimumNanos, remainingNanos);
        return new BufferProgress(
                Duration.ofNanos(behindNanos),
                Duration.ofNanos(aheadNanos),
                window.accountedBytes(),
                diskBytes,
                aheadNanos >= requiredAheadNanos);
    }

    /** Clears decoded frames and forgets all segment generations. */
    public void clear() {
        synchronized (stateLock) {
            generation.incrementAndGet();
            loadedSegments.clear();
            loadedDiskBytes.clear();
            loadingSegments.clear();
        }
        memoryBuffer.clear();
    }

    /** Stops new requests and clears the session-local decoded state. */
    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        clear();
    }

    private CompletionStage<Void> loadSegments(
            List<SegmentCache.SegmentRef> requested,
            long requestGeneration) {
        if (requested.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        AtomicInteger next = new AtomicInteger();
        int workerCount = Math.min(options.maxParallelFetches(), requested.size());
        List<CompletionStage<Void>> workers = new ArrayList<>(workerCount);
        for (int index = 0; index < workerCount; index++) {
            workers.add(loadWorker(requested, requestGeneration, next));
        }
        CompletableFuture<?>[] futures = workers.stream()
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    private CompletionStage<Void> loadWorker(
            List<SegmentCache.SegmentRef> requested,
            long requestGeneration,
            AtomicInteger next) {
        int index = next.getAndIncrement();
        if (index >= requested.size()) {
            return CompletableFuture.completedFuture(null);
        }
        return loadSegment(requested.get(index), requestGeneration)
                .thenCompose(ignored -> loadWorker(requested, requestGeneration, next));
    }

    private CompletionStage<Void> loadSegment(
            SegmentCache.SegmentRef segment,
            long requestGeneration) {
        synchronized (stateLock) {
            ensureOpen();
            ensureGeneration(requestGeneration);
            if (loadedSegments.contains(segment.ordinal())) {
                return CompletableFuture.completedFuture(null);
            }
            CompletionStage<Void> existing = loadingSegments.get(segment.ordinal());
            if (existing != null) {
                return existing;
            }
            CompletableFuture<Void> result = new CompletableFuture<>();
            loadingSegments.put(segment.ordinal(), result);
            startSegmentLoad(segment, requestGeneration, result);
            return result;
        }
    }

    private boolean areLoaded(List<SegmentCache.SegmentRef> requested) {
        synchronized (stateLock) {
            ensureOpen();
            return requested.stream()
                    .map(SegmentCache.SegmentRef::ordinal)
                    .allMatch(loadedSegments::contains);
        }
    }

    private void startSegmentLoad(
            SegmentCache.SegmentRef segment,
            long requestGeneration,
            CompletableFuture<Void> result) {
        CompletionStage<CacheEntryLease> leaseStage;
        try {
            leaseStage = segmentCache.ensureAvailable(replay, segment);
        } catch (RuntimeException exception) {
            finishSegmentLoad(segment, result, exception);
            return;
        }
        CompletionStage<ReplaySegmentReader.DecodedSegment> decodedStage = leaseStage.thenCompose(lease -> {
            CompletionStage<ReplaySegmentReader.DecodedSegment> decoded;
            try {
                decoded = CompletableFuture.supplyAsync(
                        () -> readSegment(lease, segment),
                        ioExecutor);
            } catch (RuntimeException exception) {
                lease.close();
                return CompletableFuture.failedFuture(exception);
            }
            return decoded.whenComplete((ignored, failure) -> lease.close());
        });
        decodedStage.thenApplyAsync(
                        decoded -> publishDecoded(segment, requestGeneration, decoded),
                        ioExecutor)
                .whenComplete((ignored, failure) -> finishSegmentLoad(segment, result, failure));
    }

    private ReplaySegmentReader.DecodedSegment readSegment(
            CacheEntryLease lease,
            SegmentCache.SegmentRef segment) {
        try {
            return validateDecoded(segmentReader.read(lease.path()), segment);
        } catch (Exception exception) {
            throw new SegmentDecodeException(segment, exception);
        }
    }

    private ReplaySegmentReader.DecodedSegment validateDecoded(
            ReplaySegmentReader.DecodedSegment decoded,
            SegmentCache.SegmentRef segment) {
        if (!replay.manifest().adapterId().equals(decoded.header().adapterId())) {
            throw new IllegalArgumentException("segment adapter id does not match replay manifest");
        }
        if (decoded.header().startElapsedNanos() != segment.startNanos()
                || decoded.header().endElapsedNanos() != segment.endNanos()) {
            throw new IllegalArgumentException("segment time bounds do not match segment reference");
        }
        return decoded;
    }

    private ReplaySegmentReader.DecodedSegment publishDecoded(
            SegmentCache.SegmentRef segment,
            long requestGeneration,
            ReplaySegmentReader.DecodedSegment decoded) {
        synchronized (stateLock) {
            ensureOpen();
            ensureGeneration(requestGeneration);
            memoryBuffer.addAll(decoded.frames());
            loadedSegments.add(segment.ordinal());
            loadedDiskBytes.put(segment.ordinal(), segment.artifact().sizeBytes());
            return decoded;
        }
    }

    private void finishSegmentLoad(
            SegmentCache.SegmentRef segment,
            CompletableFuture<Void> result,
            Throwable failure) {
        synchronized (stateLock) {
            loadingSegments.remove(segment.ordinal(), result);
        }
        if (failure == null) {
            result.complete(null);
        } else {
            result.completeExceptionally(unwrap(failure));
        }
    }

    private void forgetOutsideRetainedWindow(Duration position, PlaybackSpeed speed) {
        long positionNanos = toNanos(position, "position");
        long lowerBound = Math.max(0L, positionNanos - toNanos(options.retainBehind(), "retainBehind"));
        long upperBound = Math.min(
                replay.manifest().durationNanos(),
                saturatingAdd(positionNanos, scaledPreload(options.preloadAhead(), speed)));
        synchronized (stateLock) {
            loadedSegments.removeIf(ordinal -> {
                SegmentCache.SegmentRef segment = segmentByOrdinal(ordinal);
                return segment == null
                        || segment.endNanos() < lowerBound
                        || segment.startNanos() > upperBound;
            });
            loadedDiskBytes.keySet().removeIf(ordinal -> !loadedSegments.contains(ordinal));
        }
    }

    private SegmentCache.SegmentRef segmentByOrdinal(int ordinal) {
        return segments.stream()
                .filter(segment -> segment.ordinal() == ordinal)
                .findFirst()
                .orElse(null);
    }

    /**
     * Calculates contiguous decoded coverage from segment bounds rather than
     * packet timestamps. A quiet interval is buffered when its segment loaded.
     */
    private LoadedCoverage loadedCoverage(long positionNanos) {
        synchronized (stateLock) {
            long startNanos = positionNanos;
            for (int index = segments.size() - 1; index >= 0; index--) {
                SegmentCache.SegmentRef segment = segments.get(index);
                if (!loadedSegments.contains(segment.ordinal())) {
                    continue;
                }
                if (segment.startNanos() > startNanos) {
                    continue;
                }
                if (segment.endNanos() < startNanos) {
                    break;
                }
                startNanos = segment.startNanos();
            }

            long endNanos = positionNanos;
            for (SegmentCache.SegmentRef segment : segments) {
                if (!loadedSegments.contains(segment.ordinal())) {
                    continue;
                }
                if (segment.endNanos() < endNanos) {
                    continue;
                }
                if (segment.startNanos() > endNanos) {
                    break;
                }
                endNanos = segment.endNanos();
            }
            return new LoadedCoverage(startNanos, endNanos);
        }
    }

    private long validatePosition(Duration position) {
        long positionNanos = toNanos(position, "position");
        if (positionNanos < 0L || positionNanos > replay.manifest().durationNanos()) {
            throw new IllegalArgumentException("position is outside replay duration");
        }
        return positionNanos;
    }

    private void ensureOpen() {
        synchronized (stateLock) {
            if (closed) {
                throw new IllegalStateException("playback buffer is closed");
            }
        }
    }

    private void ensureGeneration(long requestGeneration) {
        if (requestGeneration != generation.get()) {
            throw new IllegalStateException("playback buffer request was cleared");
        }
    }

    private static List<SegmentCache.SegmentRef> validateSegments(
            List<SegmentCache.SegmentRef> values) {
        Objects.requireNonNull(values, "segments");
        List<SegmentCache.SegmentRef> copy = values.stream()
                .map(value -> Objects.requireNonNull(value, "segments contains null"))
                .sorted(java.util.Comparator.comparingInt(SegmentCache.SegmentRef::ordinal))
                .toList();
        Set<Integer> ordinals = new HashSet<>();
        for (SegmentCache.SegmentRef segment : copy) {
            if (!ordinals.add(segment.ordinal())) {
                throw new IllegalArgumentException("duplicate segment ordinal: " + segment.ordinal());
            }
        }
        return copy;
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

    private static long toNanos(Duration value, String name) {
        Objects.requireNonNull(value, name);
        try {
            return value.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " exceeds nanosecond range", exception);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException completion
                && completion.getCause() != null) {
            return completion.getCause();
        }
        return failure;
    }

    /** Immutable progress snapshot for one requested timeline position. */
    public record BufferProgress(
            Duration bufferedBehind,
            Duration bufferedAhead,
            long memoryBytes,
            long diskBytes,
            boolean minimumResumeSatisfied) {

        /** Validates progress values and freezes duration semantics. */
        public BufferProgress {
            Objects.requireNonNull(bufferedBehind, "bufferedBehind");
            Objects.requireNonNull(bufferedAhead, "bufferedAhead");
            if (bufferedBehind.isNegative() || bufferedAhead.isNegative()) {
                throw new IllegalArgumentException("buffer durations must not be negative");
            }
            if (memoryBytes < 0L || diskBytes < 0L) {
                throw new IllegalArgumentException("buffer byte counters must not be negative");
            }
        }
    }

    private record LoadedCoverage(long startNanos, long endNanos) {
    }

    /** Wraps a segment reader failure with the segment identity that failed. */
    private static final class SegmentDecodeException extends RuntimeException {
        private SegmentDecodeException(SegmentCache.SegmentRef segment, Throwable cause) {
            super("could not decode replay segment " + segment.ordinal(), cause);
        }
    }
}
