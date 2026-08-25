package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.api.recording.ReplayBudget;
import dev.voldechse.replayframework.api.recording.ReplayCompletionReason;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Single-owner counter for clean session budgets.
 *
 * <p>This type is intentionally not thread-safe. The session appender is its
 * only mutating owner, which makes the first completion reason linearizable
 * with the corresponding fully written frame or segment.</p>
 */
final class RecordingBudgetTracker {
    private final ReplayBudget budget;
    private long payloadBytes;
    private long packetCount;
    private long lastElapsedNanos;
    private long openedSegmentCount;
    private boolean stopRequested;
    private ReplayCompletionReason completionReason;

    RecordingBudgetTracker(ReplayBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    /** Counts one complete raw frame from a delta or checkpoint artifact. */
    BudgetObservation afterFrame(RawPacketFrame frame, UnitKind unitKind) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(unitKind, "unitKind");
        payloadBytes = add(payloadBytes, frame.payload().length, "payloadBytes");
        packetCount = add(packetCount, 1L, "packetCount");
        lastElapsedNanos = Math.max(lastElapsedNanos, frame.elapsedNanos());
        evaluateFrameBudgets();
        return observation();
    }

    /** Counts one opened segment exactly once. */
    BudgetObservation afterSegmentOpened(long segmentCount) {
        if (segmentCount <= 0L) {
            throw new IllegalArgumentException("segmentCount must be positive");
        }
        long expectedSegmentCount = add(openedSegmentCount, 1L, "openedSegmentCount");
        if (segmentCount != expectedSegmentCount) {
            throw new IllegalArgumentException(
                    "segmentCount does not match tracker state: " + segmentCount);
        }
        openedSegmentCount = expectedSegmentCount;
        // maxSegmentBytes and maxSegmentDuration only rotate a segment. The
        // maxSegments budget is different: opening the last permitted segment
        // requests a clean session stop while that segment remains drainable.
        if (completionReason == null
                && budget.maxSegments().isPresent()
                && openedSegmentCount >= budget.maxSegments().getAsLong()) {
            request(ReplayCompletionReason.SEGMENT_LIMIT);
        }
        return observation();
    }

    boolean stopRequested() {
        return stopRequested;
    }

    Optional<ReplayCompletionReason> completionReason() {
        return Optional.ofNullable(completionReason);
    }

    BudgetSnapshot snapshot() {
        return new BudgetSnapshot(
                payloadBytes,
                packetCount,
                lastElapsedNanos,
                openedSegmentCount,
                stopRequested,
                Optional.ofNullable(completionReason));
    }

    private void evaluateFrameBudgets() {
        // The order is part of the deterministic tie-break when one complete
        // frame reaches several optional limits at once.
        if (budget.maxDuration().filter(this::reachesDuration).isPresent()) {
            request(ReplayCompletionReason.DURATION_LIMIT);
        } else if (budget.maxTotalBytes().isPresent()
                && payloadBytes >= budget.maxTotalBytes().getAsLong()) {
            request(ReplayCompletionReason.BYTE_LIMIT);
        } else if (budget.maxPackets().isPresent()
                && packetCount >= budget.maxPackets().getAsLong()) {
            request(ReplayCompletionReason.PACKET_LIMIT);
        }
    }

    private boolean reachesDuration(Duration duration) {
        try {
            return lastElapsedNanos >= duration.toNanos();
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private void request(ReplayCompletionReason reason) {
        if (completionReason == null) {
            completionReason = Objects.requireNonNull(reason, "reason");
            stopRequested = true;
        }
    }

    private BudgetObservation observation() {
        return new BudgetObservation(snapshot(), completionReason());
    }

    private static long add(long left, long right, String field) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new ArithmeticException(field + " overflow");
        }
    }

    enum UnitKind {
        DELTA_FRAME,
        CHECKPOINT_FRAME
    }

    record BudgetObservation(
            BudgetSnapshot snapshot,
            Optional<ReplayCompletionReason> completionReason) {
        BudgetObservation {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(completionReason, "completionReason");
        }
    }

    record BudgetSnapshot(
            long payloadBytes,
            long packetCount,
            long lastElapsedNanos,
            long openedSegmentCount,
            boolean stopRequested,
            Optional<ReplayCompletionReason> completionReason) {
        BudgetSnapshot {
            if (payloadBytes < 0L || packetCount < 0L || lastElapsedNanos < 0L
                    || openedSegmentCount < 0L) {
                throw new IllegalArgumentException("budget snapshot counters must not be negative");
            }
            Objects.requireNonNull(completionReason, "completionReason");
        }
    }
}
