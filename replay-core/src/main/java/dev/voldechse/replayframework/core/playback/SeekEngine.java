package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.adapter.PlaybackEmissionMode;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.core.playback.buffer.PrefetchPlanner;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.SeekPoint;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Reconstructs one viewer view from a floor checkpoint and replay deltas. */
final class SeekEngine {

    private static final int MAX_EMISSION_BATCH_SIZE = 1024;

    private final ReplayIndex index;
    private final long durationNanos;
    private final PlaybackTimeline.PlaybackData data;
    private final PlaybackBridge bridge;
    private final Executor continuationExecutor;

    SeekEngine(
            ReplayIndex index,
            Duration duration,
            PlaybackTimeline.PlaybackData data,
            PlaybackBridge bridge,
            Executor continuationExecutor) {
        this.index = Objects.requireNonNull(index, "index");
        this.durationNanos = toNanos(duration, "duration");
        if (durationNanos < 0L) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        this.data = Objects.requireNonNull(data, "data");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.continuationExecutor = Objects.requireNonNull(
                continuationExecutor,
                "continuationExecutor");
    }

    CompletionStage<SeekResult> seekTo(
            Duration target,
            PlaybackSpeed speed,
            boolean resumeAfterSeek,
            BooleanSupplier operationCurrent) {
        return seekTo(
                target,
                speed,
                resumeAfterSeek,
                null,
                operationCurrent);
    }

    CompletionStage<SeekResult> seekTo(
            Duration target,
            PlaybackSpeed speed,
            boolean resumeAfterSeek,
            Duration currentPosition,
            PacketScheduler.Cursor cursor,
            boolean allowIncremental,
            BooleanSupplier operationCurrent) {
        Objects.requireNonNull(currentPosition, "currentPosition");
        Objects.requireNonNull(cursor, "cursor");
        long currentNanos = toNanos(currentPosition, "currentPosition");
        if (currentNanos < 0L || currentNanos > durationNanos) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("currentPosition is outside replay duration"));
        }
        IncrementalContext context = allowIncremental
                ? new IncrementalContext(currentNanos, cursor)
                : null;
        return seekTo(target, speed, resumeAfterSeek, context, operationCurrent);
    }

    private CompletionStage<SeekResult> seekTo(
            Duration target,
            PlaybackSpeed speed,
            boolean resumeAfterSeek,
            IncrementalContext incrementalContext,
            BooleanSupplier operationCurrent) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(speed, "speed");
        Objects.requireNonNull(operationCurrent, "operationCurrent");
        if (!operationCurrent.getAsBoolean()) {
            return cancelled();
        }
        final long targetNanos;
        try {
            targetNanos = clamp(target.toNanos());
        } catch (ArithmeticException exception) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("target exceeds nanosecond range", exception));
        }

        SeekPoint point = index.seekCheckpointFloor(Duration.ofNanos(targetNanos)).orElse(null);
        if (point == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("replay index has no floor checkpoint"));
        }
        boolean incremental = canSeekIncrementally(
                point, targetNanos, incrementalContext);

        CompletionStage<PlaybackTimeline.BufferProgress> availability;
        try {
            availability = Objects.requireNonNull(
                    data.ensureAvailable(
                            Duration.ofNanos(targetNanos),
                            speed,
                            PrefetchPlanner.Direction.SEEK),
                    "ensureAvailable result");
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        return availability.thenCompose(ignored -> {
                    requireCurrent(operationCurrent);
                    return incremental
                            ? loadIncrementalFrames(
                                    targetNanos,
                                    incrementalContext.cursor())
                            : loadFrames(point, targetNanos);
                })
                .thenComposeAsync(
                        frames -> apply(
                                point,
                                targetNanos,
                                frames,
                                resumeAfterSeek,
                                operationCurrent),
                        continuationExecutor);
    }

    private CompletionStage<LoadedFrames> loadFrames(
            SeekPoint point,
            long targetNanos) {
        Duration checkpointTime = Duration.ofNanos(point.elapsedNanos());
        Duration targetEnd = Duration.ofNanos(inclusiveEnd(targetNanos));
        CompletionStage<List<RawPacketFrame>> checkpointFrames = Objects.requireNonNull(
                data.checkpointFrames(point),
                "checkpointFrames result");
        CompletionStage<List<RawPacketFrame>> deltaFrames = Objects.requireNonNull(
                data.framesBetween(checkpointTime, targetEnd),
                "framesBetween result");
        return checkpointFrames.thenCombineAsync(
                deltaFrames,
                (checkpoint, deltas) -> {
                    List<RawPacketFrame> result = new ArrayList<>(
                            checkpoint.size() + deltas.size());
                    result.addAll(checkpoint);
                    result.addAll(deltas);
                    return new LoadedFrames(
                            List.copyOf(checkpoint),
                            List.copyOf(deltas),
                            List.copyOf(result),
                            true);
                },
                continuationExecutor);
    }

    private CompletionStage<LoadedFrames> loadIncrementalFrames(
            long targetNanos,
            PacketScheduler.Cursor cursor) {
        Duration start = Duration.ofNanos(Math.max(0L, cursor.elapsedNanos()));
        Duration targetEnd = Duration.ofNanos(inclusiveEnd(targetNanos));
        CompletionStage<List<RawPacketFrame>> deltaFrames = Objects.requireNonNull(
                data.framesBetween(start, targetEnd),
                "framesBetween result");
        return deltaFrames.thenApplyAsync(frames -> {
            List<RawPacketFrame> deltas = frames.stream()
                    .map(frame -> Objects.requireNonNull(frame, "frames contains null"))
                    .filter(cursor::isBefore)
                    .filter(frame -> frame.elapsedNanos() <= targetNanos)
                    .toList();
            return new LoadedFrames(List.of(), deltas, deltas, false);
        }, continuationExecutor);
    }

    private CompletionStage<SeekResult> apply(
            SeekPoint point,
            long targetNanos,
            LoadedFrames loadedFrames,
            boolean resumeAfterSeek,
            BooleanSupplier operationCurrent) {
        requireCurrent(operationCurrent);
        if (loadedFrames.fullRebuild()) {
            bridge.resetView();
        }
        emitBatches(loadedFrames.checkpointFrames(), operationCurrent);
        emitBatches(loadedFrames.deltaFrames(), operationCurrent);
        return bridge.awaitOutboundIdle().thenApply(ignored -> {
            requireCurrent(operationCurrent);
            boolean atEnd = targetNanos >= durationNanos;
            return new SeekResult(
                    targetNanos,
                    atEnd || !resumeAfterSeek,
                    loadedFrames.frames(),
                    loadedFrames.deltaFrames(),
                    point,
                    loadedFrames.fullRebuild());
        });
    }

    private void emitBatches(
            List<RawPacketFrame> frames,
            BooleanSupplier operationCurrent) {
        for (int start = 0; start < frames.size(); start += MAX_EMISSION_BATCH_SIZE) {
            requireCurrent(operationCurrent);
            int end = Math.min(frames.size(), start + MAX_EMISSION_BATCH_SIZE);
            bridge.sendBatch(
                    frames.subList(start, end),
                    PlaybackEmissionMode.RECONSTRUCTION);
        }
    }

    private boolean canSeekIncrementally(
            SeekPoint targetCheckpoint,
            long targetNanos,
            IncrementalContext context) {
        if (context == null
                || targetNanos <= context.positionNanos()
                || context.cursor().elapsedNanos() < 0L
                || context.cursor().elapsedNanos() > context.positionNanos()) {
            return false;
        }
        SeekPoint currentCheckpoint = index.seekCheckpointFloor(
                        Duration.ofNanos(context.positionNanos()))
                .orElse(null);
        return targetCheckpoint.equals(currentCheckpoint);
    }

    private long clamp(long value) {
        return Math.max(0L, Math.min(durationNanos, value));
    }

    private long inclusiveEnd(long targetNanos) {
        if (targetNanos >= durationNanos) {
            return targetNanos;
        }
        return targetNanos + 1L;
    }

    private static long toNanos(Duration value, String name) {
        Objects.requireNonNull(value, name);
        try {
            return value.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " exceeds nanosecond range", exception);
        }
    }

    record SeekResult(
            long positionNanos,
            boolean paused,
            List<RawPacketFrame> emittedFrames,
            List<RawPacketFrame> emittedDeltaFrames,
            SeekPoint checkpoint,
            boolean fullRebuild) {

        SeekResult {
            if (positionNanos < 0L) {
                throw new IllegalArgumentException("positionNanos must not be negative");
            }
            Objects.requireNonNull(emittedFrames, "emittedFrames");
            emittedFrames = List.copyOf(emittedFrames);
            Objects.requireNonNull(emittedDeltaFrames, "emittedDeltaFrames");
            emittedDeltaFrames = List.copyOf(emittedDeltaFrames);
            Objects.requireNonNull(checkpoint, "checkpoint");
        }
    }

    private static void requireCurrent(BooleanSupplier operationCurrent) {
        if (!operationCurrent.getAsBoolean()) {
            throw new CancellationException("seek was superseded by a newer operation");
        }
    }

    private static <T> CompletionStage<T> cancelled() {
        return CompletableFuture.failedFuture(
                new CancellationException("seek was superseded by a newer operation"));
    }

    private record LoadedFrames(
            List<RawPacketFrame> checkpointFrames,
            List<RawPacketFrame> deltaFrames,
            List<RawPacketFrame> frames,
            boolean fullRebuild) {

        private LoadedFrames {
            Objects.requireNonNull(checkpointFrames, "checkpointFrames");
            Objects.requireNonNull(deltaFrames, "deltaFrames");
            Objects.requireNonNull(frames, "frames");
        }
    }

    private record IncrementalContext(
            long positionNanos,
            PacketScheduler.Cursor cursor) {

        private IncrementalContext {
            if (positionNanos < 0L) {
                throw new IllegalArgumentException("positionNanos must not be negative");
            }
            Objects.requireNonNull(cursor, "cursor");
        }
    }
}
