package dev.voldechse.replayframework.api.query;

import dev.voldechse.replayframework.api.metadata.QueryCapabilities;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataKey;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.replay.ReplayParticipantRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable PostgreSQL-independent replay query AST.
 */
public final class ReplayQuery {
    private static final int DEFAULT_LIMIT = 50;

    private final RecordingStatus status;
    private final String adapterId;
    private final Instant createdFromInclusive;
    private final Instant createdToExclusive;
    private final ParticipantFilter participant;
    private final String titleEquals;
    private final List<MetadataCriterion> metadataCriteria;
    private final List<SortSpec> sort;
    private final String cursor;
    private final int limit;

    private ReplayQuery(Builder builder) {
        this.status = builder.status;
        this.adapterId = builder.adapterId;
        this.createdFromInclusive = builder.createdFromInclusive;
        this.createdToExclusive = builder.createdToExclusive;
        this.participant = builder.participant;
        this.titleEquals = builder.titleEquals;
        this.metadataCriteria = List.copyOf(builder.metadataCriteria);
        this.sort = List.copyOf(builder.sort);
        this.cursor = builder.cursor;
        this.limit = builder.limit;
    }

    /**
     * Creates an empty query builder.
     *
     * @return query builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the optional recording status filter.
     *
     * @return optional status filter
     */
    public Optional<RecordingStatus> status() {
        return Optional.ofNullable(status);
    }

    /**
     * Returns the optional adapter identifier filter.
     *
     * @return optional adapter identifier
     */
    public Optional<String> adapterId() {
        return Optional.ofNullable(adapterId);
    }

    /**
     * Returns the optional inclusive creation-time lower bound.
     *
     * @return optional lower bound
     */
    public Optional<Instant> createdFromInclusive() {
        return Optional.ofNullable(createdFromInclusive);
    }

    /**
     * Returns the optional exclusive creation-time upper bound.
     *
     * @return optional upper bound
     */
    public Optional<Instant> createdToExclusive() {
        return Optional.ofNullable(createdToExclusive);
    }

    /**
     * Returns the optional participant filter.
     *
     * @return optional participant filter
     */
    public Optional<ParticipantFilter> participant() {
        return Optional.ofNullable(participant);
    }

    /**
     * Returns the optional exact title filter.
     *
     * @return optional title
     */
    public Optional<String> titleEquals() {
        return Optional.ofNullable(titleEquals);
    }

    /**
     * Returns immutable metadata criteria in insertion order.
     *
     * @return metadata criteria
     */
    public List<MetadataCriterion> metadataCriteria() {
        return metadataCriteria;
    }

    /**
     * Returns immutable requested sort specifications.
     *
     * @return sort specifications
     */
    public List<SortSpec> sort() {
        return sort;
    }

    /**
     * Returns the optional opaque continuation cursor.
     *
     * @return optional continuation cursor
     */
    public Optional<String> cursor() {
        return Optional.ofNullable(cursor);
    }

    /**
     * Returns the positive result limit.
     *
     * @return result limit
     */
    public int limit() {
        return limit;
    }

    /** Builder for an immutable replay query. */
    public static final class Builder {
        private RecordingStatus status;
        private String adapterId;
        private Instant createdFromInclusive;
        private Instant createdToExclusive;
        private ParticipantFilter participant;
        private String titleEquals;
        private final List<MetadataCriterion> metadataCriteria = new ArrayList<>();
        private final List<SortSpec> sort = new ArrayList<>();
        private String cursor;
        private int limit = DEFAULT_LIMIT;

        private Builder() {
        }

        /**
         * Sets the recording status filter.
         *
         * @param status status filter
         * @return this builder
         */
        public Builder status(RecordingStatus status) {
            this.status = Objects.requireNonNull(status, "status");
            return this;
        }

        /**
         * Sets the adapter identifier filter.
         *
         * @param adapterId adapter identifier
         * @return this builder
         */
        public Builder adapterId(String adapterId) {
            Objects.requireNonNull(adapterId, "adapterId");
            if (adapterId.isBlank()) {
                throw new IllegalArgumentException("adapterId must not be blank");
            }
            this.adapterId = adapterId;
            return this;
        }

        /**
         * Sets an inclusive/exclusive creation-time interval.
         *
         * @param fromInclusive inclusive lower bound
         * @param toExclusive exclusive upper bound
         * @return this builder
         */
        public Builder createdBetween(Instant fromInclusive, Instant toExclusive) {
            this.createdFromInclusive = Objects.requireNonNull(fromInclusive, "fromInclusive");
            this.createdToExclusive = Objects.requireNonNull(toExclusive, "toExclusive");
            if (!fromInclusive.isBefore(toExclusive)) {
                throw new IllegalArgumentException("fromInclusive must be before toExclusive");
            }
            return this;
        }

        /**
         * Sets a participant filter without restricting its role.
         *
         * @param playerId participant UUID
         * @return this builder
         */
        public Builder participant(UUID playerId) {
            return participant(playerId, Optional.empty());
        }

        /**
         * Sets a participant filter with a role restriction.
         *
         * @param playerId participant UUID
         * @param role participant role
         * @return this builder
         */
        public Builder participant(UUID playerId, ReplayParticipantRole role) {
            return participant(playerId, Optional.of(Objects.requireNonNull(role, "role")));
        }

        private Builder participant(UUID playerId, Optional<ReplayParticipantRole> role) {
            this.participant = new ParticipantFilter(
                    Objects.requireNonNull(playerId, "playerId"),
                    Objects.requireNonNull(role, "role"));
            return this;
        }

        /**
         * Sets an exact title filter.
         *
         * @param title exact title
         * @return this builder
         */
        public Builder titleEquals(String title) {
            Objects.requireNonNull(title, "title");
            if (title.isBlank()) {
                throw new IllegalArgumentException("title must not be blank");
            }
            this.titleEquals = title;
            return this;
        }

        /**
         * Adds an AND-linked typed metadata criterion.
         *
         * @param key metadata key
         * @param predicate predicate for the key
         * @param <T> metadata value type
         * @return this builder
         */
        public <T> Builder where(ReplayMetadataKey<T> key, MetadataPredicate<T> predicate) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(predicate, "predicate");
            QueryCapabilities.Operation operation = predicate.operation();
            if (!key.queryCapabilities().supports(operation)) {
                throw new IllegalArgumentException(
                        "metadata key does not support " + operation);
            }
            metadataCriteria.add(new MetadataCriterion(key, predicate));
            return this;
        }

        /**
         * Adds a fixed-field sort specification.
         *
         * @param field fixed replay sort field
         * @param direction sort direction
         * @return this builder
         */
        public Builder orderBy(SortField field, SortDirection direction) {
            sort.add(new FixedSort(field, direction));
            return this;
        }

        /**
         * Adds a metadata sort after validating ORDERING capability.
         *
         * @param key metadata key
         * @param direction sort direction
         * @param <T> metadata value type
         * @return this builder
         */
        public <T> Builder orderBy(ReplayMetadataKey<T> key, SortDirection direction) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(direction, "direction");
            if (!key.queryCapabilities().supports(QueryCapabilities.Operation.ORDERING)) {
                throw new IllegalArgumentException("metadata key does not support ORDERING");
            }
            sort.add(new MetadataSort(key, direction));
            return this;
        }

        /**
         * Sets an opaque continuation cursor.
         *
         * @param cursor opaque continuation cursor
         * @return this builder
         */
        public Builder cursor(String cursor) {
            Objects.requireNonNull(cursor, "cursor");
            if (cursor.isBlank()) {
                throw new IllegalArgumentException("cursor must not be blank");
            }
            this.cursor = cursor;
            return this;
        }

        /**
         * Sets the positive result limit.
         *
         * @param limit positive result limit
         * @return this builder
         */
        public Builder limit(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            this.limit = limit;
            return this;
        }

        /**
         * Builds an immutable query snapshot.
         *
         * @return immutable query
         */
        public ReplayQuery build() {
            return new ReplayQuery(this);
        }
    }

    /**
     * Participant and optional role filter.
     *
     * @param playerId participant UUID
     * @param role optional participant role
     */
    public record ParticipantFilter(UUID playerId, Optional<ReplayParticipantRole> role) {
        /** Validates participant filter values. */
        public ParticipantFilter {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(role, "role");
        }
    }

    /** Fixed replay fields available for sorting. */
    public enum SortField {
        /** Creation timestamp. */
        CREATED_AT,
        /** Replay title. */
        TITLE,
        /** Replay duration. */
        DURATION,
        /** Catalog status. */
        STATUS,
        /** Adapter identifier. */
        ADAPTER_ID,
        /** Total uncompressed replay bytes. */
        TOTAL_BYTES
    }

    /** Sort direction. */
    public enum SortDirection {
        /** Lowest value first. */
        ASCENDING,
        /** Highest value first. */
        DESCENDING
    }

    /** Sealed sort specification used by the query compiler. */
    public sealed interface SortSpec permits FixedSort, MetadataSort {
    }

    /**
     * Sort specification for a fixed replay field.
     *
     * @param field fixed replay field
     * @param direction sort direction
     */
    public record FixedSort(SortField field, SortDirection direction) implements SortSpec {
        /** Validates fixed sort values. */
        public FixedSort {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(direction, "direction");
        }
    }

    /**
     * Sort specification for a registered metadata key.
     *
     * @param key registered metadata key
     * @param direction sort direction
     */
    public record MetadataSort(
            ReplayMetadataKey<?> key,
            SortDirection direction) implements SortSpec {
        /** Validates metadata sort values. */
        public MetadataSort {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(direction, "direction");
        }
    }

    /**
     * One metadata key and its typed predicate.
     *
     * @param key metadata key
     * @param predicate metadata predicate
     */
    public record MetadataCriterion(
            ReplayMetadataKey<?> key,
            MetadataPredicate<?> predicate) {
        /** Validates criterion values. */
        public MetadataCriterion {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(predicate, "predicate");
        }
    }
}
