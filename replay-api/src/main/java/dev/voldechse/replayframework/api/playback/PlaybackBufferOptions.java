package dev.voldechse.replayframework.api.playback;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable, session-local buffering and cache budgets.
 *
 * @param preloadAhead desired forward prefetch window
 * @param retainBehind desired backward retention window
 * @param minimumResumeBuffer data required before automatic resume
 * @param memoryBudgetBytes maximum decoded in-memory buffer size
 * @param diskBudgetBytes maximum shared disk-cache size allocated to this session
 * @param maxParallelFetches maximum concurrent segment fetches
 */
public record PlaybackBufferOptions(
        Duration preloadAhead,
        Duration retainBehind,
        Duration minimumResumeBuffer,
        long memoryBudgetBytes,
        long diskBudgetBytes,
        int maxParallelFetches) {

    private static final Duration DEFAULT_PRELOAD_AHEAD = Duration.ofSeconds(30);
    private static final Duration DEFAULT_RETAIN_BEHIND = Duration.ofSeconds(30);
    private static final Duration DEFAULT_MINIMUM_RESUME_BUFFER = Duration.ofSeconds(3);
    private static final long DEFAULT_MEMORY_BUDGET_BYTES = 128L * 1024L * 1024L;
    private static final long DEFAULT_DISK_BUDGET_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int DEFAULT_MAX_PARALLEL_FETCHES = 4;

    /** Validates strict positive durations and resource budgets. */
    public PlaybackBufferOptions {
        requirePositive(preloadAhead, "preloadAhead");
        requirePositive(retainBehind, "retainBehind");
        requirePositive(minimumResumeBuffer, "minimumResumeBuffer");
        if (memoryBudgetBytes <= 0) {
            throw new IllegalArgumentException("memoryBudgetBytes must be positive");
        }
        if (diskBudgetBytes <= 0) {
            throw new IllegalArgumentException("diskBudgetBytes must be positive");
        }
        if (maxParallelFetches <= 0) {
            throw new IllegalArgumentException("maxParallelFetches must be positive");
        }
    }

    /**
     * Creates a builder with the framework defaults.
     *
     * @return options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** Builder for immutable buffering options. */
    public static final class Builder {
        private Duration preloadAhead = DEFAULT_PRELOAD_AHEAD;
        private Duration retainBehind = DEFAULT_RETAIN_BEHIND;
        private Duration minimumResumeBuffer = DEFAULT_MINIMUM_RESUME_BUFFER;
        private long memoryBudgetBytes = DEFAULT_MEMORY_BUDGET_BYTES;
        private long diskBudgetBytes = DEFAULT_DISK_BUDGET_BYTES;
        private int maxParallelFetches = DEFAULT_MAX_PARALLEL_FETCHES;

        private Builder() {
        }

        /**
         * Sets the forward prefetch window.
         *
         * @param value positive duration
         * @return this builder
         */
        public Builder preloadAhead(Duration value) {
            this.preloadAhead = Objects.requireNonNull(value, "preloadAhead");
            return this;
        }

        /**
         * Sets the backward retention window.
         *
         * @param value positive duration
         * @return this builder
         */
        public Builder retainBehind(Duration value) {
            this.retainBehind = Objects.requireNonNull(value, "retainBehind");
            return this;
        }

        /**
         * Sets the minimum buffered duration required before resuming.
         *
         * @param value positive duration
         * @return this builder
         */
        public Builder minimumResumeBuffer(Duration value) {
            this.minimumResumeBuffer = Objects.requireNonNull(value, "minimumResumeBuffer");
            return this;
        }

        /**
         * Sets the decoded in-memory budget.
         *
         * @param value positive byte count
         * @return this builder
         */
        public Builder memoryBudgetBytes(long value) {
            this.memoryBudgetBytes = value;
            return this;
        }

        /**
         * Sets the shared disk-cache budget.
         *
         * @param value positive byte count
         * @return this builder
         */
        public Builder diskBudgetBytes(long value) {
            this.diskBudgetBytes = value;
            return this;
        }

        /**
         * Sets the maximum number of concurrent fetches.
         *
         * @param value positive fetch count
         * @return this builder
         */
        public Builder maxParallelFetches(int value) {
            this.maxParallelFetches = value;
            return this;
        }

        /**
         * Builds immutable options and validates all configured limits.
         *
         * @return buffer options
         */
        public PlaybackBufferOptions build() {
            return new PlaybackBufferOptions(
                    preloadAhead,
                    retainBehind,
                    minimumResumeBuffer,
                    memoryBudgetBytes,
                    diskBudgetBytes,
                    maxParallelFetches);
        }
    }
}
