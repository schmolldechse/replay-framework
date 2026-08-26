package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.adapter.PlaybackBridge;
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
                    return loadFrames(point, targetNanos);
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
                            List.copyOf(result),
                            List.copyOf(deltas));
                },
                continuationExecutor);
    }

    private CompletionStage<SeekResult> apply(
            SeekPoint point,
            long targetNanos,
            LoadedFrames loadedFrames,
            boolean resumeAfterSeek,
            BooleanSupplier operationCurrent) {
        requireCurrent(operationCurrent);
        bridge.resetView();
        for (RawPacketFrame frame : loadedFrames.frames()) {
            requireCurrent(operationCurrent);
            bridge.send(frame);
        }
        return bridge.awaitOutboundIdle().thenApply(ignored -> {
            requireCurrent(operationCurrent);
            boolean atEnd = targetNanos >= durationNanos;
            return new SeekResult(
                    targetNanos,
                    atEnd || !resumeAfterSeek,
                    loadedFrames.frames(),
                    loadedFrames.deltaFrames(),
                    point);
        });
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
            SeekPoint checkpoint) {

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
            List<RawPacketFrame> frames,
            List<RawPacketFrame> deltaFrames) {

        private LoadedFrames {
            Objects.requireNonNull(frames, "frames");
            Objects.requireNonNull(deltaFrames, "deltaFrames");
        }
    }
}
