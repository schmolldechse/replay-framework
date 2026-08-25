package dev.voldechse.replayframework.api.recording;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable request used to start one recording session.
 *
 * <p>An empty participant set has the domain meaning that all players
 * captured by the resolved scope are selected participants. A non-empty set
 * selects the listed UUIDs while packet capture for the resolved scope may
 * still contain other players and entities.</p>
 */
public final class RecordingRequest {
    private final String title;
    private final String description;
    private final RecordingScope scope;
    private final CapturePolicy capturePolicy;
    private final ReplayBudget budget;
    private final RecordingOptions options;
    private final Set<UUID> participants;

    private RecordingRequest(Builder builder) {
        this.title = builder.title;
        this.description = builder.description;
        this.scope = builder.scope;
        this.capturePolicy = builder.capturePolicy;
        this.budget = builder.budget;
        this.options = builder.options;
        this.participants = Set.copyOf(builder.participants);
    }

    /**
     * Creates a new request builder.
     *
     * @return a new request builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the recording title.
     *
     * @return non-blank title
     */
    public String title() {
        return title;
    }

    /**
     * Returns the recording description.
     *
     * @return non-null description, possibly empty
     */
    public String description() {
        return description;
    }

    /**
     * Returns the immutable recording scope.
     *
     * @return recording scope
     */
    public RecordingScope scope() {
        return scope;
    }

    /**
     * Returns the immutable packet capture policy.
     *
     * @return capture policy
     */
    public CapturePolicy capturePolicy() {
        return capturePolicy;
    }

    /**
     * Returns the immutable recording budget.
     *
     * @return replay budget
     */
    public ReplayBudget budget() {
        return budget;
    }

    /**
     * Returns the immutable non-budget recording options.
     *
     * @return recording options
     */
    public RecordingOptions options() {
        return options;
    }

    /**
     * Returns the UUIDs selected as participants for this recording.
     *
     * @return immutable UUID set; empty means all captured players are selected
     */
    public Set<UUID> participants() {
        return participants;
    }

    /** Builder for an immutable {@link RecordingRequest}. */
    public static final class Builder {
        private String title;
        private String description;
        private RecordingScope scope;
        private CapturePolicy capturePolicy;
        private ReplayBudget budget;
        private RecordingOptions options = RecordingOptions.defaults();
        private Set<UUID> participants = new LinkedHashSet<>();

        private Builder() {
        }

        /**
         * Sets the non-blank recording title.
         *
         * @param title recording title
         * @return this builder
         */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /**
         * Sets the non-null recording description.
         *
         * @param description recording description, possibly empty
         * @return this builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the scope used for capture.
         *
         * @param scope recording scope
         * @return this builder
         */
        public Builder scope(RecordingScope scope) {
            this.scope = scope;
            return this;
        }

        /**
         * Sets the per-recording capture policy.
         *
         * @param capturePolicy capture policy
         * @return this builder
         */
        public Builder capturePolicy(CapturePolicy capturePolicy) {
            this.capturePolicy = capturePolicy;
            return this;
        }

        /**
         * Sets the per-recording budget.
         *
         * @param budget replay budget
         * @return this builder
         */
        public Builder budget(ReplayBudget budget) {
            this.budget = budget;
            return this;
        }

        /** Sets the immutable non-budget recording options. */
        public Builder options(RecordingOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /**
         * Replaces the selected participant set with a defensive copy.
         *
         * @param participants player UUIDs; an empty collection selects all captured players
         * @return this builder
         */
        public Builder participants(Collection<UUID> participants) {
            Objects.requireNonNull(participants, "participants");
            Set<UUID> copy = new LinkedHashSet<>();
            for (UUID player : participants) {
                copy.add(Objects.requireNonNull(player, "participants entry"));
            }
            this.participants = copy;
            return this;
        }

        /**
         * Validates and builds an immutable request snapshot.
         *
         * @return immutable recording request
         * @throws NullPointerException if a required value is missing
         * @throws IllegalArgumentException if the title is blank
         */
        public RecordingRequest build() {
            Objects.requireNonNull(title, "title");
            if (title.isBlank()) {
                throw new IllegalArgumentException("title must not be blank");
            }
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(capturePolicy, "capturePolicy");
            Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(options, "options");
            return new RecordingRequest(this);
        }
    }
}
