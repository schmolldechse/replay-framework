package dev.voldechse.replayframework.api.query;

import dev.voldechse.replayframework.api.metadata.QueryCapabilities;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, SQL-free predicate for one registered metadata key.
 *
 * @param <T> logical value type of the metadata key
 */
public sealed interface MetadataPredicate<T>
        permits MetadataPredicate.Exists,
                MetadataPredicate.EqualTo,
                MetadataPredicate.OneOf,
                MetadataPredicate.Between,
                MetadataPredicate.Contains {

    /**
     * Returns the capability required to execute this predicate.
     *
     * @return required query operation
     */
    QueryCapabilities.Operation operation();

    /**
     * Creates a presence predicate.
     *
     * @param <T> logical metadata value type
     * @return immutable existence predicate
     */
    static <T> MetadataPredicate<T> exists() {
        return new Exists<>();
    }

    /**
     * Creates an exact equality predicate.
     *
     * @param value expected value
     * @param <T> logical metadata value type
     * @return immutable equality predicate
     */
    static <T> MetadataPredicate<T> equalTo(T value) {
        return new EqualTo<>(Objects.requireNonNull(value, "value"));
    }

    /**
     * Creates a finite membership predicate.
     *
     * @param values accepted values
     * @param <T> logical metadata value type
     * @return immutable membership predicate
     */
    static <T> MetadataPredicate<T> oneOf(Collection<? extends T> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        return new OneOf<>(Set.copyOf(values));
    }

    /**
     * Creates an inclusive ordered range predicate.
     *
     * @param lowerInclusive lower bound
     * @param upperInclusive upper bound
     * @param <T> comparable logical metadata value type
     * @return immutable range predicate
     */
    static <T extends Comparable<? super T>> MetadataPredicate<T> between(
            T lowerInclusive,
            T upperInclusive) {
        return new Between<>(
                Objects.requireNonNull(lowerInclusive, "lowerInclusive"),
                Objects.requireNonNull(upperInclusive, "upperInclusive"));
    }

    /**
     * Creates a containment predicate whose exact semantics come from the key codec.
     *
     * @param value containment value
     * @param <T> logical metadata value type
     * @return immutable containment predicate
     */
    static <T> MetadataPredicate<T> contains(T value) {
        return new Contains<>(Objects.requireNonNull(value, "value"));
    }

    /**
     * Presence predicate.
     *
     * @param <T> logical metadata value type
     */
    record Exists<T>() implements MetadataPredicate<T> {
        @Override
        public QueryCapabilities.Operation operation() {
            return QueryCapabilities.Operation.EXISTENCE;
        }
    }

    /**
     * Exact equality predicate.
     *
     * @param value expected value
     * @param <T> logical metadata value type
     */
    record EqualTo<T>(T value) implements MetadataPredicate<T> {
        /** Validates the expected value. */
        public EqualTo {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public QueryCapabilities.Operation operation() {
            return QueryCapabilities.Operation.EQUALITY;
        }
    }

    /**
     * Finite membership predicate.
     *
     * @param values accepted values
     * @param <T> logical metadata value type
     */
    record OneOf<T>(Set<T> values) implements MetadataPredicate<T> {
        /** Validates and copies accepted values. */
        public OneOf {
            Objects.requireNonNull(values, "values");
            if (values.isEmpty()) {
                throw new IllegalArgumentException("values must not be empty");
            }
            values = Set.copyOf(values);
        }

        @Override
        public QueryCapabilities.Operation operation() {
            return QueryCapabilities.Operation.EQUALITY;
        }
    }

    /**
     * Inclusive ordered range predicate.
     *
     * @param lowerInclusive lower bound
     * @param upperInclusive upper bound
     * @param <T> comparable logical metadata value type
     */
    record Between<T extends Comparable<? super T>>(
            T lowerInclusive,
            T upperInclusive) implements MetadataPredicate<T> {
        /** Validates the ordered range. */
        public Between {
            Objects.requireNonNull(lowerInclusive, "lowerInclusive");
            Objects.requireNonNull(upperInclusive, "upperInclusive");
            if (lowerInclusive.compareTo(upperInclusive) > 0) {
                throw new IllegalArgumentException("lowerInclusive must not exceed upperInclusive");
            }
        }

        @Override
        public QueryCapabilities.Operation operation() {
            return QueryCapabilities.Operation.ORDERING;
        }
    }

    /**
     * JSON-compatible containment predicate.
     *
     * @param value containment value
     * @param <T> logical metadata value type
     */
    record Contains<T>(T value) implements MetadataPredicate<T> {
        /** Validates the containment value. */
        public Contains {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public QueryCapabilities.Operation operation() {
            return QueryCapabilities.Operation.CONTAINMENT;
        }
    }
}
