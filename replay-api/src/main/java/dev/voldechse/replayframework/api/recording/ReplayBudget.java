package dev.voldechse.replayframework.api.recording;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Immutable segment, session and resource-protection limits for a recording.
 *
 * <p>{@code maxSegmentBytes} is the required structural rotation limit. The
 * remaining limits are optional and do not create hidden session bounds when
 * omitted.</p>
 */
public final class ReplayBudget {
    private final long maxSegmentBytes;
    private final Duration maxSegmentDuration;
    private final Duration maxDuration;
    private final Long maxTotalBytes;
    private final Long maxPackets;
    private final Long maxSegments;
    private final Long maxQueueBytes;
    private final Long maxPendingUploadBytes;

    private ReplayBudget(Builder builder) {
        this.maxSegmentBytes = builder.maxSegmentBytes;
        this.maxSegmentDuration = builder.maxSegmentDuration;
        this.maxDuration = builder.maxDuration;
        this.maxTotalBytes = builder.maxTotalBytes;
        this.maxPackets = builder.maxPackets;
        this.maxSegments = builder.maxSegments;
        this.maxQueueBytes = builder.maxQueueBytes;
        this.maxPendingUploadBytes = builder.maxPendingUploadBytes;
    }

    /**
     * Creates a new budget builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the required uncompressed structural segment-byte limit.
     *
     * @return positive segment limit
     */
    public long maxSegmentBytes() {
        return maxSegmentBytes;
    }

    /**
     * Returns the optional structural segment-duration limit.
     *
     * @return optional positive duration
     */
    public Optional<Duration> maxSegmentDuration() {
        return Optional.ofNullable(maxSegmentDuration);
    }

    /**
     * Returns the optional total recording-duration limit.
     *
     * @return optional positive duration
     */
    public Optional<Duration> maxDuration() {
        return Optional.ofNullable(maxDuration);
    }

    /**
     * Returns the optional total uncompressed payload-byte limit.
     *
     * @return optional positive byte limit
     */
    public OptionalLong maxTotalBytes() {
        return optionalLong(maxTotalBytes);
    }

    /**
     * Returns the optional packet-count limit.
     *
     * @return optional positive packet count
     */
    public OptionalLong maxPackets() {
        return optionalLong(maxPackets);
    }

    /**
     * Returns the optional session segment-count limit.
     *
     * @return optional positive segment count
     */
    public OptionalLong maxSegments() {
        return optionalLong(maxSegments);
    }

    /**
     * Returns the optional capture-queue byte limit.
     *
     * @return optional positive queue-byte limit
     */
    public OptionalLong maxQueueBytes() {
        return optionalLong(maxQueueBytes);
    }

    /**
     * Returns the optional not-yet-persisted or uploaded byte limit.
     *
     * @return optional positive pending-upload byte limit
     */
    public OptionalLong maxPendingUploadBytes() {
        return optionalLong(maxPendingUploadBytes);
    }

    private static OptionalLong optionalLong(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    /** Builder for an immutable {@link ReplayBudget}. */
    public static final class Builder {
        private Long maxSegmentBytes;
        private Duration maxSegmentDuration;
        private Duration maxDuration;
        private Long maxTotalBytes;
        private Long maxPackets;
        private Long maxSegments;
        private Long maxQueueBytes;
        private Long maxPendingUploadBytes;

        private Builder() {
        }

        /**
         * Sets the required uncompressed structural segment-byte limit.
         *
         * @param bytes positive byte limit
         * @return this builder
         */
        public Builder maxSegmentBytes(long bytes) {
            this.maxSegmentBytes = bytes;
            return this;
        }

        /**
         * Sets the optional structural segment-duration limit.
         *
         * @param duration positive duration
         * @return this builder
         */
        public Builder maxSegmentDuration(Duration duration) {
            this.maxSegmentDuration = Objects.requireNonNull(duration, "maxSegmentDuration");
            return this;
        }

        /**
         * Sets the optional total recording-duration limit.
         *
         * @param duration positive duration
         * @return this builder
         */
        public Builder maxDuration(Duration duration) {
            this.maxDuration = Objects.requireNonNull(duration, "maxDuration");
            return this;
        }

        /**
         * Sets the optional total uncompressed payload-byte limit.
         *
         * @param bytes positive byte limit
         * @return this builder
         */
        public Builder maxTotalBytes(long bytes) {
            this.maxTotalBytes = bytes;
            return this;
        }

        /**
         * Sets the optional packet-count limit.
         *
         * @param packets positive packet count
         * @return this builder
         */
        public Builder maxPackets(long packets) {
            this.maxPackets = packets;
            return this;
        }

        /**
         * Sets the optional session segment-count limit.
         *
         * @param segments positive segment count
         * @return this builder
         */
        public Builder maxSegments(long segments) {
            this.maxSegments = segments;
            return this;
        }

        /**
         * Sets the optional capture-queue byte limit.
         *
         * @param bytes positive byte limit
         * @return this builder
         */
        public Builder maxQueueBytes(long bytes) {
            this.maxQueueBytes = bytes;
            return this;
        }

        /**
         * Sets the optional not-yet-persisted or uploaded byte limit.
         *
         * @param bytes positive byte limit
         * @return this builder
         */
        public Builder maxPendingUploadBytes(long bytes) {
            this.maxPendingUploadBytes = bytes;
            return this;
        }

        /**
         * Builds and validates an immutable budget snapshot.
         *
         * @return immutable replay budget
         * @throws IllegalArgumentException if a configured value is not positive
         */
        public ReplayBudget build() {
            requireRequiredPositive(maxSegmentBytes, "maxSegmentBytes");
            requireOptionalPositive(maxTotalBytes, "maxTotalBytes");
            requireOptionalPositive(maxPackets, "maxPackets");
            requireOptionalPositive(maxSegments, "maxSegments");
            requireOptionalPositive(maxQueueBytes, "maxQueueBytes");
            requireOptionalPositive(maxPendingUploadBytes, "maxPendingUploadBytes");
            requirePositive(maxSegmentDuration, "maxSegmentDuration");
            requirePositive(maxDuration, "maxDuration");
            return new ReplayBudget(this);
        }

        private static void requireRequiredPositive(Long value, String name) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }

        private static void requireOptionalPositive(Long value, String name) {
            if (value != null && value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }

        private static void requirePositive(Duration value, String name) {
            if (value != null && (value.isZero() || value.isNegative())) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }
}
