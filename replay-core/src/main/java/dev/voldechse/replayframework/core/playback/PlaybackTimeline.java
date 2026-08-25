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

    record BufferProgress(Duration bufferedBehind, Duration bufferedAhead) {
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
    private final Object stateLock = new Object();
    private final PlaybackClock clock;
    private final SeekEngine seekEngine;
    private final PacketScheduler scheduler;
    private PlaybackStatus status = PlaybackStatus.PREPARING;
    private boolean desiredPlaying;
    private boolean prepared;
    private boolean closed;
    private long operationGeneration;
    private BufferProgress lastProgress = new BufferProgress(Duration.ZERO, Duration.ZERO);

    PlaybackTimeline(
            Duration duration,
            ReplayIndex index,
            PlaybackData data,
            PlaybackBridge bridge,
            PlaybackBufferOptions options,
            Executor commandExecutor,
            LongSupplier nowNanos) {
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
        this.clock = new PlaybackClock(durationNanos, PlaybackSpeed.NORMAL, nowNanos);
        this.seekEngine = new SeekEngine(
                Objects.requireNonNull(index, "index"),
                duration,
                data,
                bridge,
                commandExecutor);
        this.scheduler = new PacketScheduler(clock, data, bridge, new SchedulerListener());
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
                                progress.bufferedAhead()));
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
                return new BufferProgress(progress.bufferedBehind(), progress.bufferedAhead());
            }

            @Override
            public void close() {
                buffer.close();
            }
        };
    }

    void play() {
        commandExecutor.execute(() -> {
            synchronized (stateLock) {
                if (isTerminal()) {
                    return;
                }
                if (status == PlaybackStatus.ENDED) {
                    return;
                }
                desiredPlaying = true;
                if (!prepared) {
                    status = PlaybackStatus.PREPARING;
                    return;
                }
                if (status == PlaybackStatus.BUFFERING) {
                    scheduler.start();
                    scheduler.wake();
                    return;
                }
                clock.play(nowNanos.getAsLong());
                status = PlaybackStatus.PLAYING;
                scheduler.start();
                scheduler.wake();
            }
        });
    }

    void pause() {
        commandExecutor.execute(() -> {
            synchronized (stateLock) {
                if (isTerminal()) {
                    return;
                }
                desiredPlaying = false;
                clock.pause(nowNanos.getAsLong());
                if (status == PlaybackStatus.PLAYING
                        || status == PlaybackStatus.BUFFERING) {
                    status = PlaybackStatus.PAUSED;
                }
                scheduler.wake();
            }
        });
    }

    void speed(PlaybackSpeed speed) {
        Objects.requireNonNull(speed, "speed");
        commandExecutor.execute(() -> {
            synchronized (stateLock) {
                if (isTerminal()) {
                    return;
                }
                clock.setSpeed(speed, nowNanos.getAsLong());
                scheduler.wake();
            }
        });
    }

    CompletionStage<PlaybackSnapshot> prepare() {
        return enqueueAsync(() -> {
            synchronized (stateLock) {
                if (closed || status == PlaybackStatus.FAILED) {
                    return failedTerminal();
                }
                if (prepared) {
                    return CompletableFuture.completedFuture(snapshotLocked());
                }
                status = PlaybackStatus.BUFFERING;
            }
            long generation = nextOperation();
            return seekEngine.seekTo(Duration.ZERO, clock.speed(), false)
                    .thenApplyAsync(result -> {
                        synchronized (stateLock) {
                            if (generation != operationGeneration || isTerminal()) {
                                throw new IllegalStateException(
                                        "playback preparation is no longer current");
                            }
                            applySeekResult(result);
                            prepared = true;
                            if (desiredPlaying && result.positionNanos() < durationNanos) {
                                clock.play(nowNanos.getAsLong());
                                status = PlaybackStatus.PLAYING;
                                scheduler.start();
                                scheduler.wake();
                            } else {
                                status = PlaybackStatus.PAUSED;
                            }
                            return snapshotLocked();
                        }
                    }, commandExecutor)
                    .exceptionallyCompose(failure -> failPreparation(failure));
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
            return beginSeek(Duration.ofNanos(target));
        });
    }

    CompletionStage<PlaybackSnapshot> closeAsync() {
        return enqueueAsync(() -> {
            synchronized (stateLock) {
                if (closed) {
                    return CompletableFuture.completedFuture(snapshotLocked());
                }
                closed = true;
                desiredPlaying = false;
                status = PlaybackStatus.CLOSED;
                operationGeneration++;
            }
            CompletionStage<Void> stopped = scheduler.stop();
            return stopped
                    .thenRunAsync(this::closeResources, commandExecutor)
                    .thenApplyAsync(ignored -> snapshot(), commandExecutor);
        });
    }

    @Override
    public void close() {
        closeAsync();
    }

    PlaybackSnapshot snapshot() {
        synchronized (stateLock) {
            if (!closed) {
                lastProgress = data.progress(Duration.ofNanos(clock.positionNanos(
                        nowNanos.getAsLong())));
            }
            return snapshotLocked();
        }
    }

    void runSchedulerOnce(long nowNanos) {
        scheduler.runOnce(nowNanos);
    }

    private CompletionStage<PlaybackSnapshot> seek(Duration target) {
        return enqueueAsync(() -> beginSeek(target));
    }

    private CompletionStage<PlaybackSnapshot> beginSeek(Duration target) {
        final boolean resume;
        synchronized (stateLock) {
            if (isTerminal()) {
                return failedTerminal();
            }
            resume = desiredPlaying;
            clock.pause(nowNanos.getAsLong());
            status = PlaybackStatus.BUFFERING;
        }
        long generation = nextOperation();
        return seekEngine.seekTo(target, clock.speed(), resume)
                .thenApplyAsync(result -> {
                    synchronized (stateLock) {
                        if (generation != operationGeneration || isTerminal()) {
                            throw new IllegalStateException("seek is no longer current");
                        }
                        applySeekResult(result);
                        if (result.positionNanos() >= durationNanos) {
                            desiredPlaying = false;
                            status = PlaybackStatus.ENDED;
                        } else if (!desiredPlaying) {
                            status = PlaybackStatus.PAUSED;
                        } else {
                            clock.play(nowNanos.getAsLong());
                            status = PlaybackStatus.PLAYING;
                            scheduler.start();
                            scheduler.wake();
                        }
                        return snapshotLocked();
                    }
                }, commandExecutor)
                .exceptionallyCompose(failure -> failOperation(failure));
    }

    private void applySeekResult(SeekEngine.SeekResult result) {
        clock.seekTo(result.positionNanos(), nowNanos.getAsLong());
        scheduler.resetCursor(result.emittedFrames());
        lastProgress = data.progress(Duration.ofNanos(result.positionNanos()));
    }

    private CompletionStage<PlaybackSnapshot> failPreparation(Throwable failure) {
        synchronized (stateLock) {
            failLocked(failure);
            return CompletableFuture.failedFuture(unwrap(failure));
        }
    }

    private CompletionStage<PlaybackSnapshot> failOperation(Throwable failure) {
        synchronized (stateLock) {
            failLocked(failure);
            return CompletableFuture.failedFuture(unwrap(failure));
        }
    }

    private void failLocked(Throwable failure) {
        if (closed || status == PlaybackStatus.CLOSED) {
            return;
        }
        desiredPlaying = false;
        status = PlaybackStatus.FAILED;
        operationGeneration++;
        scheduler.stop().thenRunAsync(this::closeResources, commandExecutor);
    }

    private long nextOperation() {
        synchronized (stateLock) {
            return ++operationGeneration;
        }
    }

    private boolean isTerminal() {
        return closed || status == PlaybackStatus.CLOSED || status == PlaybackStatus.FAILED;
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
                synchronized (stateLock) {
                    if (!isTerminal() && desiredPlaying) {
                        clock.pause(nowNanos.getAsLong());
                        status = PlaybackStatus.BUFFERING;
                    }
                }
            });
        }

        @Override
        public void onBufferReady() {
            commandExecutor.execute(() -> {
                synchronized (stateLock) {
                    if (!isTerminal() && desiredPlaying && status == PlaybackStatus.BUFFERING) {
                        clock.play(nowNanos.getAsLong());
                        status = PlaybackStatus.PLAYING;
                    }
                }
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
                synchronized (stateLock) {
                    if (!isTerminal()) {
                        desiredPlaying = false;
                        clock.pause(nowNanos.getAsLong());
                        status = PlaybackStatus.ENDED;
                    }
                }
            });
        }

        @Override
        public void onFailure(Throwable failure) {
            commandExecutor.execute(() -> {
                synchronized (stateLock) {
                    failLocked(failure);
                }
            });
        }
    }
}
