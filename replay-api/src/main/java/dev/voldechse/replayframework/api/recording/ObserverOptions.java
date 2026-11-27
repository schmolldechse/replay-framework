package dev.voldechse.replayframework.api.recording;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * How the virtual observers of a recording behave.
 *
 * <p>Virtual observers receive the scene on the recording's behalf. Fixed
 * observers cover the bounded regions of a scope; companion observers follow
 * players through whole worlds and stay where a player left, died or logged
 * out. No budget is limited by default.</p>
 */
public final class ObserverOptions {

    private static final Duration DEFAULT_GRACE_PERIOD = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_VIEW_DISTANCE = 8;
    private static final Duration DEFAULT_STATE_SAMPLE_INTERVAL = Duration.ofSeconds(1);
    private static final ObserverOptions DEFAULT = builder().build();

    private final Duration gracePeriod;
    private final OptionalInt maxEyesPerRegion;
    private final OptionalInt maxCompanions;
    private final int maxViewDistance;
    private final Duration stateSampleInterval;

    private ObserverOptions(Builder builder) {
        this.gracePeriod = builder.gracePeriod;
        this.maxEyesPerRegion = builder.maxEyesPerRegion;
        this.maxCompanions = builder.maxCompanions;
        this.maxViewDistance = builder.maxViewDistance;
        this.stateSampleInterval = builder.stateSampleInterval;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Returns the defaults: 30 seconds grace, no budget, view distance 8. */
    public static ObserverOptions defaults() {
        return DEFAULT;
    }

    /**
     * How long a companion keeps observing after its player logged out or
     * left the recorded worlds. Zero closes it at once.
     */
    public Duration gracePeriod() {
        return gracePeriod;
    }

    /** The most fixed observers one region may use; empty means unlimited. */
    public OptionalInt maxEyesPerRegion() {
        return maxEyesPerRegion;
    }

    /** The most players followed at once; empty means unlimited. */
    public OptionalInt maxCompanions() {
        return maxCompanions;
    }

    /**
     * The largest chunk radius one fixed observer loads. A larger radius
     * means fewer observers for a region and more chunks around it.
     */
    public int maxViewDistance() {
        return maxViewDistance;
    }

    /**
     * How often the observers read the state of entities that no client
     * receives without opening them, such as the inventory of a llama or a
     * chest minecart. A change is recorded at the next reading.
     */
    public Duration stateSampleInterval() {
        return stateSampleInterval;
    }

    /** Builder for an immutable observer option snapshot. */
    public static final class Builder {
        private Duration gracePeriod = DEFAULT_GRACE_PERIOD;
        private OptionalInt maxEyesPerRegion = OptionalInt.empty();
        private OptionalInt maxCompanions = OptionalInt.empty();
        private int maxViewDistance = DEFAULT_MAX_VIEW_DISTANCE;
        private Duration stateSampleInterval = DEFAULT_STATE_SAMPLE_INTERVAL;

        private Builder() {
        }

        /** Sets the non-negative grace period after a followed player is gone. */
        public Builder gracePeriod(Duration gracePeriod) {
            Objects.requireNonNull(gracePeriod, "gracePeriod");
            if (gracePeriod.isNegative()) {
                throw new IllegalArgumentException("gracePeriod must not be negative");
            }
            this.gracePeriod = gracePeriod;
            return this;
        }

        /** Limits the fixed observers of one region; the recording fails to start above it. */
        public Builder maxEyesPerRegion(int maxEyes) {
            if (maxEyes < 1) {
                throw new IllegalArgumentException("maxEyesPerRegion must be positive");
            }
            this.maxEyesPerRegion = OptionalInt.of(maxEyes);
            return this;
        }

        /** Sets how often entity state is read; at least one server tick (50 ms). */
        public Builder stateSampleInterval(Duration interval) {
            Objects.requireNonNull(interval, "interval");
            if (interval.toMillis() < 50L) {
                throw new IllegalArgumentException("stateSampleInterval must be at least 50 ms");
            }
            this.stateSampleInterval = interval;
            return this;
        }

        /** Removes the limit of fixed observers per region. */
        public Builder unlimitedEyesPerRegion() {
            this.maxEyesPerRegion = OptionalInt.empty();
            return this;
        }

        /** Limits the players followed at once; further players are not followed. */
        public Builder maxCompanions(int maxCompanions) {
            if (maxCompanions < 0) {
                throw new IllegalArgumentException("maxCompanions must not be negative");
            }
            this.maxCompanions = OptionalInt.of(maxCompanions);
            return this;
        }

        /** Removes the limit of followed players. */
        public Builder unlimitedCompanions() {
            this.maxCompanions = OptionalInt.empty();
            return this;
        }

        /** Sets the largest chunk radius of one fixed observer, from 3 to 32. */
        public Builder maxViewDistance(int maxViewDistance) {
            if (maxViewDistance < 3 || maxViewDistance > 32) {
                throw new IllegalArgumentException("maxViewDistance must be between 3 and 32");
            }
            this.maxViewDistance = maxViewDistance;
            return this;
        }

        public ObserverOptions build() {
            return new ObserverOptions(this);
        }
    }
}
