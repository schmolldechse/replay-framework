package dev.voldechse.replayframework.api.recording;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable options that control recording behavior independently from size
 * and resource budgets.
 */
public final class RecordingOptions {
    private static final Duration DEFAULT_CHECKPOINT_INTERVAL = Duration.ofSeconds(30);
    private static final RecordingOptions DEFAULT = new RecordingOptions(DEFAULT_CHECKPOINT_INTERVAL);

    private final Duration checkpointInterval;

    private RecordingOptions(Duration checkpointInterval) {
        this.checkpointInterval = checkpointInterval;
    }

    /** Returns a builder initialized with the framework defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the interval between regular checkpoints. */
    public Duration checkpointInterval() {
        return checkpointInterval;
    }

    /** Returns the immutable default option snapshot. */
    public static RecordingOptions defaults() {
        return DEFAULT;
    }

    /** Builder for an immutable recording option snapshot. */
    public static final class Builder {
        private Duration checkpointInterval = DEFAULT_CHECKPOINT_INTERVAL;

        private Builder() {
        }

        /** Sets the positive, nanosecond-representable checkpoint interval. */
        public Builder checkpointInterval(Duration interval) {
            this.checkpointInterval = Objects.requireNonNull(interval, "checkpointInterval");
            return this;
        }

        /** Validates and creates an immutable option snapshot. */
        public RecordingOptions build() {
            Duration interval = Objects.requireNonNull(checkpointInterval, "checkpointInterval");
            if (interval.isZero() || interval.isNegative()) {
                throw new IllegalArgumentException("checkpointInterval must be positive");
            }
            try {
                if (interval.toNanos() <= 0L) {
                    throw new IllegalArgumentException("checkpointInterval must be positive");
                }
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException(
                        "checkpointInterval must be representable in nanoseconds", overflow);
            }
            return new RecordingOptions(interval);
        }
    }
}
