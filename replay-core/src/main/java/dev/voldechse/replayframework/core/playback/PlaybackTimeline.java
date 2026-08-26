package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.api.playback.PlaybackBufferOptions;
import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import dev.voldechse.replayframework.core.playback.buffer.PlaybackBuffer;
import dev.voldechse.replayframework.core.playback.buffer.PrefetchPlanner;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.SeekPoint;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Owns the state and serialized commands of one viewer timeline. */
final class PlaybackTimeline implements AutoCloseable {

    @FunctionalInterface
    interface CheckpointLoader {
        CompletionStage<List<RawPacketFrame>> load(SeekPoint point);
    }

    interface PlaybackData {
        CompletionStage<BufferProgress> ensureAvailable(
                Duration position,
                PlaybackSpeed speed,
                PrefetchPlanner.Direction direction);

        CompletionStage<List<RawPacketFrame>> checkpointFrames(SeekPoint point);

        CompletionStage<List<RawPacketFrame>> framesBetween(
                Duration startInclusive,
                Duration endInclusive);

        BufferProgress progress(Duration position);

        void close();
    }

    /** Internal observer for state transitions produced by the timeline. */
    interface Listener {
        default void onStatusChanged(
                PlaybackStatus previous,
                PlaybackStatus current,
                PlaybackSnapshot snapshot) {
        }

        default void onSpeedChanged(
                PlaybackSpeed previous,
                PlaybackSpeed current,
                PlaybackSnapshot snapshot) {
        }

        default void onSeeked(Duration requestedPosition, PlaybackSnapshot snapshot) {
        }

        default void onBufferChanged(PlaybackSnapshot snapshot) {
        }

        default void onCompleted(PlaybackSnapshot snapshot, Throwable failure) {
        }

        static Listener noOp() {
            return new Listener() {
            };
        }
    }

    record BufferProgress(
            Duration bufferedBehind,
            Duration bufferedAhead,
            boolean minimumResumeSatisfied) {
        BufferProgress {
            Objects.requireNonNull(bufferedBehind, "bufferedBehind");
            Objects.requireNonNull(bufferedAhead, "bufferedAhead");
            if (bufferedBehind.isNegative() || bufferedAhead.isNegative()) {
                throw new IllegalArgumentException("buffer durations must not be negative");
            }
        }
    }

    private final long durationNanos;
    private final PlaybackData data;
    private final PlaybackBridge bridge;
    private final Executor commandExecutor;
    private final LongSupplier nowNanos;
    private final Listener listener;
    private final Object stateLock = new Object();
    private final PlaybackClock clock;
    private final SeekEngine seekEngine;
    private final PacketScheduler scheduler;
    private PlaybackStatus status = PlaybackStatus.PREPARING;
    private boolean desiredPlaying;
    private boolean prepared;
    private boolean closed;
    private boolean completionNotified;
    private boolean resourcesClosed;
    private long operationGeneration;
    private BufferProgress lastProgress = new BufferProgress(Duration.ZERO, Duration.ZERO, false);

    PlaybackTimeline(
            Duration duration,
            ReplayIndex index,
            PlaybackData data,
            PlaybackBridge bridge,
            PlaybackBufferOptions options,
            Executor commandExecutor,
            LongSupplier nowNanos) {
        this(
                duration,
                index,
                data,
                bridge,
                options,
                commandExecutor,
                nowNanos,
                Listener.noOp());
    }

    PlaybackTimeline(
            Duration duration,
            ReplayIndex index,
            PlaybackData data,
            PlaybackBridge bridge,
            PlaybackBufferOptions options,
            Executor commandExecutor,
            LongSupplier nowNanos,
            Listener listener) {
        this.durationNanos = toNanos(duration, "duration");
        if (durationNanos < 0L) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        this.data = Objects.requireNonNull(data, "data");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        Objects.requireNonNull(options, "options");
        this.commandExecutor = new SerialExecutor(
                Objects.requireNonNull(commandExecutor, "commandExecutor"));
        this.nowNanos = Objects.requireNonNull(nowNanos, "nowNanos");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.clock = new PlaybackClock(durationNanos, PlaybackSpeed.NORMAL, nowNanos);
        this.seekEngine = new SeekEngine(
                Objects.requireNonNull(index, "index"),
                duration,
                data,
                bridge,
                commandExecutor);
        this.scheduler = new PacketScheduler(
                clock,
                data,
                bridge,
                new SchedulerListener(),
                options.preloadAhead());
    }

    static PlaybackData adapt(
            PlaybackBuffer buffer,
            CheckpointLoader checkpointLoader) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(checkpointLoader, "checkpointLoader");
        return new PlaybackData() {
            @Override
            public CompletionStage<BufferProgress> ensureAvailable(
                    Duration position,
                    PlaybackSpeed speed,
                    PrefetchPlanner.Direction direction) {
                return buffer.ensureAvailable(position, speed, direction)
                        .thenApply(progress -> new BufferProgress(
                                progress.bufferedBehind(),
                                progress.bufferedAhead(),
                                progress.minimumResumeSatisfied()));
            }

            @Override
            public CompletionStage<List<RawPacketFrame>> checkpointFrames(SeekPoint point) {
                return checkpointLoader.load(point);
            }

            @Override
            public CompletionStage<List<RawPacketFrame>> framesBetween(
                    Duration startInclusive,
                    Duration endInclusive) {
                return buffer.framesBetween(startInclusive, endInclusive);
            }

            @Override
            public BufferProgress progress(Duration position) {
                PlaybackBuffer.BufferProgress progress = buffer.progress(position);
                return new BufferProgress(
                        progress.bufferedBehind(),
                        progress.bufferedAhead(),
                        progress.minimumResumeSatisfied());
            }

            @Override
            public void close() {
                buffer.close();
            }
        };
    }

    void play() {
        commandExecutor.execute(() -> {
            StatusTransition transition;
            synchronized (stateLock) {
                if (isTerminal()) {
                    return;
                }
                if (status == PlaybackStatus.ENDED) {
                    return;
                }
                desiredPlaying = true;
                if (!prepared) {
                    transition = setStatusLocked(PlaybackStatus.PREPARING);
                } else if (status == PlaybackStatus.PLAYING) {
                    clock.play(nowNanos.getAsLong());
                    transition = null;
                    scheduler.start();
                    scheduler.wake();
                } else {
                    transition = setStatusLocked(PlaybackStatus.BUFFERING);
                    scheduler.start();
                    scheduler.wake();
                }
            }
            notifyStatus(transition);
        });
    }

    void pause() {
        commandExecutor.execute(() -> {
            StatusTransition transition;
            synchronized (stateLock) {
                if (isTerminal()) {
                    return;
                }
                desiredPlaying = false;
                clock.pause(nowNanos.getAsLong());
                if (status == PlaybackStatus.PLAYING
                        || status == PlaybackStatus.BUFFERING) {
                    transition = setStatusLocked(PlaybackStatus.PAUSED);
                } else {
                    transition = null;
                }
                scheduler.wake();
            }
            notifyStatus(transition);
        });
    }

    void speed(PlaybackSpeed speed) {
        Objects.requireNonNull(speed, "speed");
        commandExecutor.execute(() -> {
            SpeedTransition transition = null;
            synchronized (stateLock) {
                if (isTerminal()) {
                    return;
                }
                PlaybackSpeed previous = clock.speed();
                clock.setSpeed(speed, nowNanos.getAsLong());
                if (previous != speed) {
                    transition = new SpeedTransition(previous, speed, snapshotLocked());
                    scheduler.invalidatePrefetch();
                } else {
                    scheduler.wake();
                }
            }
            notifySpeed(transition);
        });
    }

    CompletionStage<PlaybackSnapshot> prepare() {
        return enqueueAsync(() -> {
            StatusTransition preparing;
            synchronized (stateLock) {
                if (closed || status == PlaybackStatus.FAILED) {
                    return failedTerminal();
                }
                if (prepared) {
                    return CompletableFuture.completedFuture(snapshotLocked());
                }
                preparing = setStatusLocked(PlaybackStatus.BUFFERING);
            }
            notifyStatus(preparing);
            long generation = nextOperation();
            return seekEngine.seekTo(
                            Duration.ZERO,
                            clock.speed(),
                            false,
                            () -> isCurrentOperation(generation))
                    .thenApplyAsync(result -> {
                        StatusTransition preparedTransition;
                        BufferProgress previousProgress;
                        PlaybackSnapshot snapshot;
                        synchronized (stateLock) {
                            if (generation != operationGeneration || isTerminal()) {
                                throw new IllegalStateException(
                                        "playback preparation is no longer current");
                            }
                            previousProgress = applySeekResult(result);
                            prepared = true;
                            if (desiredPlaying && result.positionNanos() < durationNanos) {
                                clock.play(nowNanos.getAsLong());
                                preparedTransition = setStatusLocked(PlaybackStatus.PLAYING);
                                scheduler.start();
                                scheduler.wake();
                            } else {
                                preparedTransition = setStatusLocked(PlaybackStatus.PAUSED);
                            }
                            snapshot = snapshotLocked();
                        }
                        notifyBuffer(previousProgress, snapshot);
                        notifyStatus(preparedTransition);
                        return snapshot;
                    }, commandExecutor)
                    .exceptionallyCompose(failure -> cancelled(failure)
                            ? CompletableFuture.completedFuture(snapshot())
                            : failPreparation(failure));
        });
    }

    CompletionStage<PlaybackSnapshot> restart() {
        return seek(Duration.ZERO);
    }

    CompletionStage<PlaybackSnapshot> seekTo(Duration position) {
        Objects.requireNonNull(position, "position");
        return seek(position);
    }

    CompletionStage<PlaybackSnapshot> seekBy(Duration delta) {
        Objects.requireNonNull(delta, "delta");
        long generation = nextOperation();
        scheduler.suspendEmission();
        bridge.discardQueuedReplayPackets();
        return enqueueAsync(() -> {
            long current = clock.positionNanos(nowNanos.getAsLong());
            long deltaNanos;
            try {
                deltaNanos = delta.toNanos();
            } catch (ArithmeticException exception) {
                deltaNanos = delta.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
            long target;
            try {
                target = Math.addExact(current, deltaNanos);
            } catch (ArithmeticException exception) {
                target = deltaNanos < 0L ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
            return beginSeek(Duration.ofNanos(target), generation);
        });
    }

    CompletionStage<PlaybackSnapshot> closeAsync() {
        return enqueueAsync(() -> {
            StatusTransition closedTransition;
            synchronized (stateLock) {
                if (closed) {
                    return CompletableFuture.completedFuture(snapshotLocked());
                }
                closed = true;
                desiredPlaying = false;
                closedTransition = setStatusLocked(PlaybackStatus.CLOSED);
                operationGeneration++;
            }
            notifyStatus(closedTransition);
            CompletionStage<Void> stopped = scheduler.stop();
            return stopped
                    .thenRunAsync(() -> {
                        RuntimeException failure = null;
                        try {
                            closeResources();
                        } catch (RuntimeException exception) {
                            failure = exception;
                        }
                        PlaybackSnapshot snapshot = snapshot();
                        notifyCompleted(snapshot, failure);
                        if (failure != null) {
                            throw failure;
                        }
                    }, commandExecutor)
                    .thenApplyAsync(ignored -> snapshot(), commandExecutor);
        });
    }

    /** Fails the timeline when the adapter reports an asynchronous send error. */
    void failFromBridge(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        enqueueAsync(() -> failOperation(failure)).exceptionally(ignored -> null);
    }

    @Override
    public void close() {
        closeAsync();
    }

    PlaybackSnapshot snapshot() {
        BufferProgress previousProgress;
        PlaybackSnapshot snapshot;
        synchronized (stateLock) {
            previousProgress = lastProgress;
            if (!closed) {
                lastProgress = data.progress(Duration.ofNanos(clock.positionNanos(
                        nowNanos.getAsLong())));
            }
            snapshot = snapshotLocked();
        }
        notifyBuffer(previousProgress, snapshot);
        return snapshot;
    }

    void runSchedulerOnce(long nowNanos) {
        scheduler.runOnce(nowNanos);
    }

    private CompletionStage<PlaybackSnapshot> seek(Duration target) {
        long generation = nextOperation();
        scheduler.suspendEmission();
        bridge.discardQueuedReplayPackets();
        return enqueueAsync(() -> beginSeek(target, generation));
    }

    private CompletionStage<PlaybackSnapshot> beginSeek(Duration target, long generation) {
        final boolean resume;
        final Duration requestedPosition = normalizeRequestedPosition(target);
        StatusTransition bufferingTransition;
        synchronized (stateLock) {
            if (!isCurrentOperationLocked(generation)) {
                return CompletableFuture.failedFuture(
                        new java.util.concurrent.CancellationException(
                                "seek was superseded before it started"));
            }
            if (isTerminal()) {
                return failedTerminal();
            }
            resume = desiredPlaying;
            clock.pause(nowNanos.getAsLong());
            bufferingTransition = setStatusLocked(PlaybackStatus.BUFFERING);
        }
        notifyStatus(bufferingTransition);
        return seekEngine.seekTo(
                        target,
                        clock.speed(),
                        resume,
                        () -> isCurrentOperation(generation))
                .thenApplyAsync(result -> {
                    StatusTransition finalTransition;
                    BufferProgress previousProgress;
                    PlaybackSnapshot snapshot;
                    synchronized (stateLock) {
                        if (generation != operationGeneration || isTerminal()) {
                            throw new IllegalStateException("seek is no longer current");
                        }
                        previousProgress = applySeekResult(result);
                        scheduler.resumeEmission();
                        if (result.positionNanos() >= durationNanos) {
                            desiredPlaying = false;
                            finalTransition = setStatusLocked(PlaybackStatus.ENDED);
                        } else if (!desiredPlaying) {
                            finalTransition = setStatusLocked(PlaybackStatus.PAUSED);
                        } else {
                            clock.play(nowNanos.getAsLong());
                            finalTransition = setStatusLocked(PlaybackStatus.PLAYING);
                            scheduler.start();
                            scheduler.wake();
                        }
                        snapshot = snapshotLocked();
                    }
                    notifyBuffer(previousProgress, snapshot);
                    notifyStatus(finalTransition);
                    notifySeek(requestedPosition, snapshot);
                    if (snapshot.status() == PlaybackStatus.ENDED) {
                        notifyCompleted(snapshot, null);
                    }
                    return snapshot;
                }, commandExecutor)
                .exceptionallyCompose(failure -> cancelled(failure)
                        ? CompletableFuture.completedFuture(snapshot())
                        : failOperation(failure));
    }

    private BufferProgress applySeekResult(SeekEngine.SeekResult result) {
        BufferProgress previousProgress = lastProgress;
        clock.seekTo(result.positionNanos(), nowNanos.getAsLong());
        scheduler.resetCursor(
                result.emittedDeltaFrames(),
                result.checkpoint().elapsedNanos());
        lastProgress = data.progress(Duration.ofNanos(result.positionNanos()));
        return previousProgress;
    }

    private CompletionStage<PlaybackSnapshot> failPreparation(Throwable failure) {
        FailureTransition transition;
        synchronized (stateLock) {
            transition = failLocked(failure);
        }
        finishFailure(transition);
        return CompletableFuture.failedFuture(unwrap(failure));
    }

    private CompletionStage<PlaybackSnapshot> failOperation(Throwable failure) {
        FailureTransition transition;
        synchronized (stateLock) {
            transition = failLocked(failure);
        }
        finishFailure(transition);
        return CompletableFuture.failedFuture(unwrap(failure));
    }

    private FailureTransition failLocked(Throwable failure) {
        if (isTerminal()) {
            return null;
        }
        desiredPlaying = false;
        StatusTransition statusTransition = setStatusLocked(PlaybackStatus.FAILED);
        operationGeneration++;
        return new FailureTransition(statusTransition, unwrap(failure));
    }

    private void finishFailure(FailureTransition transition) {
        if (transition == null) {
            return;
        }
        notifyStatus(transition.statusTransition());
        scheduler.stop().thenRunAsync(() -> {
            RuntimeException cleanupFailure = null;
            try {
                closeResources();
            } catch (RuntimeException exception) {
                cleanupFailure = exception;
            }
            PlaybackSnapshot snapshot = snapshot();
            notifyCompleted(snapshot, transition.failure());
            if (cleanupFailure != null) {
                if (transition.failure() != null && transition.failure() != cleanupFailure) {
                    cleanupFailure.addSuppressed(transition.failure());
                }
                throw cleanupFailure;
            }
        }, commandExecutor);
    }

    private StatusTransition setStatusLocked(PlaybackStatus next) {
        Objects.requireNonNull(next, "next");
        if (status == next) {
            return null;
        }
        PlaybackStatus previous = status;
        status = next;
        return new StatusTransition(previous, next, snapshotLocked());
    }

    private void notifyStatus(StatusTransition transition) {
        if (transition == null) {
            return;
        }
        try {
            listener.onStatusChanged(
                    transition.previous(),
                    transition.current(),
                    transition.snapshot());
        } catch (RuntimeException ignored) {
            // Internal observers must not change timeline state.
        }
    }

    private void notifySpeed(SpeedTransition transition) {
        if (transition == null) {
            return;
        }
        try {
            listener.onSpeedChanged(
                    transition.previous(),
                    transition.current(),
                    transition.snapshot());
        } catch (RuntimeException ignored) {
            // Internal observers must not change timeline state.
        }
    }

    private void notifySeek(Duration requestedPosition, PlaybackSnapshot snapshot) {
        try {
            listener.onSeeked(requestedPosition, snapshot);
        } catch (RuntimeException ignored) {
            // Internal observers must not change timeline state.
        }
    }

    private void notifyBuffer(BufferProgress previous, PlaybackSnapshot snapshot) {
        BufferProgress current;
        synchronized (stateLock) {
            current = lastProgress;
        }
        if (previous.equals(current)) {
            return;
        }
        try {
            listener.onBufferChanged(snapshot);
        } catch (RuntimeException ignored) {
            // Internal observers must not change timeline state.
        }
    }

    private void notifyCompleted(PlaybackSnapshot snapshot, Throwable failure) {
        synchronized (stateLock) {
            if (completionNotified) {
                return;
            }
            completionNotified = true;
        }
        try {
            listener.onCompleted(snapshot, failure);
        } catch (RuntimeException ignored) {
            // Internal observers must not change timeline state.
        }
    }

    private static Duration normalizeRequestedPosition(Duration target) {
        try {
            return Duration.ofNanos(Math.max(0L, target.toNanos()));
        } catch (ArithmeticException exception) {
            return target.isNegative()
                    ? Duration.ZERO
                    : Duration.ofNanos(Long.MAX_VALUE);
        }
    }

    private record StatusTransition(
            PlaybackStatus previous,
            PlaybackStatus current,
            PlaybackSnapshot snapshot) {
    }

    private record SpeedTransition(
            PlaybackSpeed previous,
            PlaybackSpeed current,
            PlaybackSnapshot snapshot) {
    }

    private record FailureTransition(StatusTransition statusTransition, Throwable failure) {
    }

    private long nextOperation() {
        synchronized (stateLock) {
            return ++operationGeneration;
        }
    }

    private boolean isCurrentOperation(long generation) {
        synchronized (stateLock) {
            return isCurrentOperationLocked(generation);
        }
    }

    private boolean isCurrentOperationLocked(long generation) {
        return generation == operationGeneration && !isTerminal();
    }

    private static boolean cancelled(Throwable failure) {
        return unwrap(failure) instanceof java.util.concurrent.CancellationException;
    }

    private boolean isTerminal() {
        return closed
                || status == PlaybackStatus.CLOSED
                || status == PlaybackStatus.FAILED;
    }

    private CompletionStage<PlaybackSnapshot> failedTerminal() {
        return CompletableFuture.failedFuture(
                new IllegalStateException("playback timeline is terminal"));
    }

    private PlaybackSnapshot snapshotLocked() {
        long positionNanos = clock.positionNanos(nowNanos.getAsLong());
        return new PlaybackSnapshot(
                Duration.ofNanos(positionNanos),
                Duration.ofNanos(durationNanos),
                clock.speed(),
                status,
                lastProgress.bufferedBehind(),
                lastProgress.bufferedAhead());
    }

    private void closeResources() {
        synchronized (stateLock) {
            if (resourcesClosed) {
                return;
            }
            resourcesClosed = true;
        }
        RuntimeException failure = null;
        try {
            data.close();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            bridge.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private <T> CompletionStage<T> enqueueAsync(Supplier<CompletionStage<T>> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            commandExecutor.execute(() -> {
                try {
                    action.get().whenComplete((value, failure) -> {
                        if (failure == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(unwrap(failure));
                        }
                    });
                } catch (RuntimeException exception) {
                    result.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
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

    /** Serializes state-changing callbacks while retaining the supplied execution context. */
    private static final class SerialExecutor implements Executor {
        private final Executor delegate;
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean running;

        private SerialExecutor(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command, "command");
            synchronized (queue) {
                queue.addLast(command);
                if (running) {
                    return;
                }
                running = true;
            }
            scheduleNext();
        }

        private void scheduleNext() {
            Runnable next;
            synchronized (queue) {
                next = queue.pollFirst();
                if (next == null) {
                    running = false;
                    return;
                }
            }
            try {
                delegate.execute(() -> {
                    try {
                        next.run();
                    } finally {
                        scheduleNext();
                    }
                });
            } catch (RuntimeException exception) {
                synchronized (queue) {
                    running = false;
                }
                throw exception;
            }
        }
    }

    private final class SchedulerListener implements PacketScheduler.Listener {
        @Override
        public void onBuffering() {
            commandExecutor.execute(() -> {
                StatusTransition transition = null;
                synchronized (stateLock) {
                    if (!isTerminal() && desiredPlaying) {
                        clock.pause(nowNanos.getAsLong());
                        transition = setStatusLocked(PlaybackStatus.BUFFERING);
                    }
                }
                notifyStatus(transition);
            });
        }

        @Override
        public void onBufferReady() {
            commandExecutor.execute(() -> {
                scheduler.acknowledgeBufferReady();
                StatusTransition transition = null;
                synchronized (stateLock) {
                    if (!isTerminal()
                            && desiredPlaying
                            && (status == PlaybackStatus.BUFFERING
                            || status == PlaybackStatus.PAUSED)) {
                        clock.play(nowNanos.getAsLong());
                        transition = setStatusLocked(PlaybackStatus.PLAYING);
                    }
                }
                notifyStatus(transition);
            });
        }

        @Override
        public boolean shouldContinueBuffering() {
            synchronized (stateLock) {
                return !isTerminal() && desiredPlaying;
            }
        }

        @Override
        public void onEnded() {
            commandExecutor.execute(() -> {
                StatusTransition transition = null;
                PlaybackSnapshot snapshot = null;
                synchronized (stateLock) {
                    if (!isTerminal()) {
                        desiredPlaying = false;
                        clock.pause(nowNanos.getAsLong());
                        transition = setStatusLocked(PlaybackStatus.ENDED);
                        snapshot = snapshotLocked();
                    }
                }
                notifyStatus(transition);
                if (snapshot != null) {
                    notifyCompleted(snapshot, null);
                }
            });
        }

        @Override
        public void onFailure(Throwable failure) {
            commandExecutor.execute(() -> {
                FailureTransition transition;
                synchronized (stateLock) {
                    transition = failLocked(failure);
                }
                finishFailure(transition);
            });
        }
    }
}
