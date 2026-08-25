package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.core.capture.CapturedPacket;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Adapter-neutral checkpoint trigger and ordinal state machine for one
 * recording session.
 */
final class CheckpointScheduler {
    private final long intervalNanos;
    private final CheckpointEncoder encoder;
    private final RecordingScope scope;
    private final CheckpointRequestSource requestSource;
    private long nextDueNanos;
    private long lastObservedElapsedNanos = -1L;
    private long lastObservedServerTick = -1L;
    private Point pendingPoint;
    private long lastWrittenElapsedNanos = -1L;
    private long lastWrittenServerTick = -1L;
    private int nextOrdinal = 1;

    CheckpointScheduler(
            Duration interval,
            CheckpointEncoder encoder,
            RecordingScope scope,
            CheckpointRequestSource requestSource) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("checkpoint interval must be positive");
        }
        try {
            intervalNanos = interval.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "checkpoint interval must be representable in nanoseconds", overflow);
        }
        if (intervalNanos <= 0L) {
            throw new IllegalArgumentException("checkpoint interval must be positive");
        }
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.requestSource = Objects.requireNonNull(requestSource, "requestSource");
        nextDueNanos = intervalNanos;
    }

    /** Creates one regular request when the next interval has been reached. */
    Optional<CheckpointEncoder.CheckpointRequest> dueForFrame(CapturedPacket packet) {
        Objects.requireNonNull(packet, "packet");
        return dueForElapsed(packet.captureTimeNanos(), packet.serverTick());
    }

    /** Package-internal variant used after the session clock was rebased. */
    Optional<CheckpointEncoder.CheckpointRequest> dueForElapsed(
            long elapsedNanos,
            long serverTick) {
        validateObservedPoint(elapsedNanos, serverTick);
        if (elapsedNanos < nextDueNanos) {
            return Optional.empty();
        }
        advanceDue(elapsedNanos);
        Point point = new Point(elapsedNanos, serverTick);
        if (isAlreadyPendingOrWritten(point)) {
            return Optional.empty();
        }
        pendingPoint = point;
        return Optional.of(requestSource.create(
                elapsedNanos, serverTick, CheckpointEncoder.CheckpointKind.PERIODIC));
    }

    /** Creates an immediate request for an adapter-verified dimension change. */
    Optional<CheckpointEncoder.CheckpointRequest> dueForDimensionChange(
            long elapsedNanos,
            long serverTick) {
        validateObservedPoint(elapsedNanos, serverTick);
        Point point = new Point(elapsedNanos, serverTick);
        if (isAlreadyPendingOrWritten(point)) {
            return Optional.empty();
        }
        pendingPoint = point;
        return Optional.of(requestSource.create(
                elapsedNanos, serverTick, CheckpointEncoder.CheckpointKind.DIMENSION_CHANGE));
    }

    /** Encodes through the adapter port; the caller controls the execution thread. */
    CompletionStage<ReplayCheckpoint> encode(CheckpointEncoder.CheckpointRequest request) {
        Objects.requireNonNull(request, "request");
        return encoder.encode(request);
    }

    /** Registers the already persisted initial checkpoint at ordinal zero. */
    void registerInitial(ReplayCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (checkpoint.ordinal() != 0) {
            throw new IllegalArgumentException("initial checkpoint ordinal must be zero");
        }
        if (lastWrittenElapsedNanos >= 0L) {
            throw new IllegalStateException("initial checkpoint is already registered");
        }
        lastWrittenElapsedNanos = checkpoint.elapsedNanos();
        lastWrittenServerTick = lastTick(checkpoint);
        lastObservedElapsedNanos = Math.max(lastObservedElapsedNanos, checkpoint.elapsedNanos());
        lastObservedServerTick = Math.max(lastObservedServerTick, lastWrittenServerTick);
    }

    /** Commits one checkpoint only after its atomic file write succeeded. */
    void markWritten(ReplayCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (checkpoint.ordinal() != nextOrdinal) {
            throw new IllegalArgumentException(
                    "checkpoint ordinal is not the next session ordinal: " + checkpoint.ordinal());
        }
        long serverTick = pendingPoint == null
                ? lastTick(checkpoint)
                : pendingPoint.serverTick;
        if (isBefore(checkpoint.elapsedNanos(), serverTick,
                lastWrittenElapsedNanos, lastWrittenServerTick)) {
            throw new IllegalArgumentException("checkpoint time or tick moved backwards");
        }
        if (pendingPoint != null
                && (pendingPoint.elapsedNanos != checkpoint.elapsedNanos()
                || pendingPoint.serverTick != serverTick)) {
            throw new IllegalArgumentException("written checkpoint does not match pending trigger");
        }
        try {
            nextOrdinal = Math.addExact(nextOrdinal, 1);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("checkpoint ordinal overflow", overflow);
        }
        lastWrittenElapsedNanos = checkpoint.elapsedNanos();
        lastWrittenServerTick = serverTick;
        pendingPoint = null;
    }

    int nextOrdinal() {
        return nextOrdinal;
    }

    private void validateObservedPoint(long elapsedNanos, long serverTick) {
        if (elapsedNanos < 0L || serverTick < 0L) {
            throw new IllegalArgumentException("checkpoint point must not be negative");
        }
        if (lastObservedElapsedNanos >= 0L
                && isBefore(elapsedNanos, serverTick,
                lastObservedElapsedNanos, lastObservedServerTick)) {
            throw new IllegalArgumentException("checkpoint trigger moved backwards");
        }
        lastObservedElapsedNanos = elapsedNanos;
        lastObservedServerTick = serverTick;
    }

    private void advanceDue(long elapsedNanos) {
        long due = nextDueNanos;
        while (due <= elapsedNanos && due <= Long.MAX_VALUE - intervalNanos) {
            due += intervalNanos;
        }
        nextDueNanos = due > elapsedNanos ? due : Long.MAX_VALUE;
    }

    private boolean isAlreadyPendingOrWritten(Point point) {
        return point.equals(pendingPoint)
                || (lastWrittenElapsedNanos >= 0L
                && point.elapsedNanos == lastWrittenElapsedNanos
                && point.serverTick == lastWrittenServerTick);
    }

    private static boolean isBefore(
            long elapsedNanos,
            long serverTick,
            long previousElapsedNanos,
            long previousServerTick) {
        return elapsedNanos < previousElapsedNanos
                || (elapsedNanos == previousElapsedNanos && serverTick < previousServerTick);
    }

    private static long lastTick(ReplayCheckpoint checkpoint) {
        return checkpoint.initializationFrames().isEmpty()
                ? 0L
                : checkpoint.initializationFrames()
                        .get(checkpoint.initializationFrames().size() - 1)
                        .serverTick();
    }

    private record Point(long elapsedNanos, long serverTick) {
    }
}

@FunctionalInterface
interface CheckpointRequestSource {
    CheckpointEncoder.CheckpointRequest create(
            long elapsedNanos,
            long serverTick,
            CheckpointEncoder.CheckpointKind kind);
}
