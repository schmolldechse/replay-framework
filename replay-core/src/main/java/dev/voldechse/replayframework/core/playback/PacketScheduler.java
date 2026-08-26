package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.core.playback.buffer.PrefetchPlanner;
import dev.voldechse.replayframework.format.RawPacketFrame;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Schedules due replay frames on one virtual worker. */
final class PacketScheduler {

    interface Listener {
        void onBuffering();

        void onBufferReady();

        boolean shouldContinueBuffering();

        void onEnded();

        void onFailure(Throwable failure);
    }

    private static final long PARK_NANOS = 1_000_000L;
    private static final int MAX_FRAMES_PER_BATCH = 1024;
    private static final Comparator<RawPacketFrame> FRAME_ORDER = Comparator
            .comparingLong(RawPacketFrame::elapsedNanos)
            .thenComparingLong(RawPacketFrame::serverTick)
            .thenComparingInt(RawPacketFrame::sequence);

    private final PlaybackClock clock;
    private final PlaybackTimeline.PlaybackData data;
    private final PlaybackBridge bridge;
    private final Listener listener;
    private final long prefetchRefreshNanos;
    private final AtomicBoolean availabilityPending = new AtomicBoolean();
    private final AtomicBoolean framesPending = new AtomicBoolean();
    private final AtomicBoolean bufferReadyPending = new AtomicBoolean();
    private final AtomicBoolean minimumResumeSatisfied = new AtomicBoolean();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicBoolean endedReported = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private volatile boolean stopped;
    private volatile boolean emissionSuspended;
    private volatile Thread worker;
    private volatile long nextPrefetchPositionNanos = Long.MIN_VALUE;
    private CompletableFuture<Void> stopCompletion = new CompletableFuture<>();
    private long cursorElapsedNanos = -1L;
    private long cursorServerTick = -1L;
    private int cursorSequence = -1;

    PacketScheduler(
            PlaybackClock clock,
            PlaybackTimeline.PlaybackData data,
            PlaybackBridge bridge,
            Listener listener) {
        this(clock, data, bridge, listener, Duration.ofSeconds(30));
    }

    PacketScheduler(
            PlaybackClock clock,
            PlaybackTimeline.PlaybackData data,
            PlaybackBridge bridge,
            Listener listener,
            Duration preloadAhead) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.data = Objects.requireNonNull(data, "data");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.listener = Objects.requireNonNull(listener, "listener");
        long preloadNanos = Objects.requireNonNull(preloadAhead, "preloadAhead").toNanos();
        if (preloadNanos <= 0L) {
            throw new IllegalArgumentException("preloadAhead must be positive");
        }
        this.prefetchRefreshNanos = Math.max(1L, preloadNanos / 2L);
    }

    void start() {
        synchronized (lifecycleLock) {
            if (stopped || worker != null) {
                return;
            }
            worker = Thread.startVirtualThread(this::runLoop);
        }
    }

    void wake() {
        Thread currentWorker = worker;
        if (currentWorker != null) {
            LockSupport.unpark(currentWorker);
        }
    }

    void acknowledgeBufferReady() {
        bufferReadyPending.set(false);
    }

    void suspendEmission() {
        emissionSuspended = true;
        invalidatePrefetch();
    }

    void resumeEmission() {
        emissionSuspended = false;
        wake();
    }

    CompletionStage<Void> stop() {
        synchronized (lifecycleLock) {
            if (!stopped) {
                stopped = true;
                wake();
            }
            if (worker == null) {
                stopCompletion.complete(null);
            }
        }
        return stopCompletion;
    }

    void resetCursor(List<RawPacketFrame> emittedDeltaFrames, long checkpointNanos) {
        Objects.requireNonNull(emittedDeltaFrames, "emittedDeltaFrames");
        if (checkpointNanos < 0L) {
            throw new IllegalArgumentException("checkpointNanos must not be negative");
        }
        endedReported.set(false);
        invalidatePrefetch();
        RawPacketFrame last = emittedDeltaFrames.stream()
                .max(FRAME_ORDER)
                .orElse(null);
        if (last == null) {
            // Checkpoint records use their own local sequence numbers.  They
            // must never become the cursor for the segment stream: a segment
            // may still contain a delta at the same replay time.
            cursorElapsedNanos = checkpointNanos;
            cursorServerTick = -1L;
            cursorSequence = -1;
            return;
        }
        cursorElapsedNanos = last.elapsedNanos();
        cursorServerTick = last.serverTick();
        cursorSequence = last.sequence();
    }

    void runOnce(long nowNanos) {
        if (stopped || emissionSuspended) {
            return;
        }
        if (clock.playing()) {
            bufferReadyPending.set(false);
        }
        if (!clock.playing()) {
            if (!shouldContinueBuffering()) {
                bufferReadyPending.set(false);
                return;
            }
            if (bufferReadyPending.get()
                    || availabilityPending.get()
                    || framesPending.get()) {
                return;
            }
        }
        long positionNanos = clock.positionNanos(nowNanos);
        Duration position = Duration.ofNanos(positionNanos);
        requestPrefetch(position);
        if (stopped) {
            return;
        }

        if (!framesPending.compareAndSet(false, true)) {
            return;
        }
        CompletionStage<List<RawPacketFrame>> frames;
        try {
            frames = Objects.requireNonNull(
                    data.framesBetween(
                            cursorStart(),
                            Duration.ofNanos(inclusiveEnd(positionNanos))),
                    "framesBetween result");
        } catch (RuntimeException exception) {
            framesPending.set(false);
            reportFailure(exception);
            return;
        }
        if (!frames.toCompletableFuture().isDone()) {
            // PlaybackBuffer completes an in-memory frame range immediately.
            // A pending stage therefore denotes an actual segment load rather
            // than executor scheduling latency.
            onBuffering();
        }
        frames.whenComplete((availableFrames, failure) -> {
            framesPending.set(false);
            if (failure != null) {
                reportFailure(unwrap(failure));
                return;
            }
            try {
                if (!clock.playing()) {
                    if (!shouldContinueBuffering()) {
                        wake();
                        return;
                    }
                    if (minimumResumeSatisfied.get()) {
                        signalBufferReady();
                    }
                }
                boolean complete = emit(availableFrames, positionNanos);
                if (complete
                        && positionNanos >= clock.durationNanos()
                        && endedReported.compareAndSet(false, true)) {
                    listener.onEnded();
                }
            } catch (RuntimeException exception) {
                reportFailure(exception);
            }
            wake();
        });
    }

    void invalidatePrefetch() {
        nextPrefetchPositionNanos = Long.MIN_VALUE;
        minimumResumeSatisfied.set(false);
        wake();
    }

    private void requestPrefetch(Duration position) {
        long positionNanos = position.toNanos();
        if (positionNanos < nextPrefetchPositionNanos) {
            return;
        }
        if (!availabilityPending.compareAndSet(false, true)) {
            return;
        }
        nextPrefetchPositionNanos = saturatingAdd(positionNanos, prefetchRefreshNanos);
        CompletionStage<PlaybackTimeline.BufferProgress> availability;
        try {
            availability = Objects.requireNonNull(
                    data.ensureAvailable(
                            position,
                            clock.speed(),
                            PrefetchPlanner.Direction.FORWARD),
                    "ensureAvailable result");
        } catch (RuntimeException exception) {
            availabilityPending.set(false);
            reportFailure(exception);
            return;
        }
        availability.whenComplete((progress, failure) -> {
            availabilityPending.set(false);
            if (failure != null) {
                reportFailure(unwrap(failure));
            } else {
                minimumResumeSatisfied.set(progress.minimumResumeSatisfied());
                if (!clock.playing()
                        && shouldContinueBuffering()
                        && progress.minimumResumeSatisfied()) {
                    signalBufferReady();
                }
                wake();
            }
        });
    }

    private void onBuffering() {
        listener.onBuffering();
    }

    private void signalBufferReady() {
        if (!clock.playing()
                && shouldContinueBuffering()
                && bufferReadyPending.compareAndSet(false, true)) {
            listener.onBufferReady();
        }
    }

    private boolean shouldContinueBuffering() {
        return listener.shouldContinueBuffering();
    }

    private void runLoop() {
        try {
            while (!stopped) {
                runOnce(clock.nowNanos());
                LockSupport.parkNanos(PARK_NANOS);
            }
        } catch (RuntimeException exception) {
            reportFailure(exception);
        } finally {
            synchronized (lifecycleLock) {
                worker = null;
                stopCompletion.complete(null);
            }
        }
    }

    private boolean emit(List<RawPacketFrame> frames, long positionNanos) {
        Objects.requireNonNull(frames, "frames");
        List<RawPacketFrame> ordered = frames.stream()
                .map(frame -> Objects.requireNonNull(frame, "frames contains null"))
                .sorted(FRAME_ORDER)
                .toList();
        int emitted = 0;
        for (RawPacketFrame frame : ordered) {
            if (stopped || emissionSuspended) {
                return false;
            }
            if (frame.elapsedNanos() > positionNanos) {
                continue;
            }
            if (!isAfterCursor(frame)) {
                continue;
            }
            if (emitted == MAX_FRAMES_PER_BATCH) {
                return false;
            }
            bridge.send(frame);
            cursorElapsedNanos = frame.elapsedNanos();
            cursorServerTick = frame.serverTick();
            cursorSequence = frame.sequence();
            emitted++;
        }
        return true;
    }

    private boolean isAfterCursor(RawPacketFrame frame) {
        if (cursorElapsedNanos < 0L) {
            return true;
        }
        if (frame.elapsedNanos() != cursorElapsedNanos) {
            return frame.elapsedNanos() > cursorElapsedNanos;
        }
        if (frame.serverTick() != cursorServerTick) {
            return frame.serverTick() > cursorServerTick;
        }
        return frame.sequence() > cursorSequence;
    }

    private Duration cursorStart() {
        return cursorElapsedNanos < 0L
                ? Duration.ZERO
                : Duration.ofNanos(cursorElapsedNanos);
    }

    private long inclusiveEnd(long positionNanos) {
        if (positionNanos >= clock.durationNanos()) {
            return positionNanos;
        }
        return positionNanos + 1L;
    }

    private void reportFailure(Throwable failure) {
        if (failureReported.compareAndSet(false, true)) {
            listener.onFailure(failure);
            stop();
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException completion
                && completion.getCause() != null) {
            return completion.getCause();
        }
        return failure;
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
