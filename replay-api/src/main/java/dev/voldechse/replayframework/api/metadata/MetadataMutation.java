package dev.voldechse.replayframework.api.metadata;

import dev.voldechse.replayframework.api.id.ReplayId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.key.Key;

/**
 * Immutable optimistic-locking mutation for one replay's metadata.
 */
public final class MetadataMutation {
    private final ReplayId replayId;
    private final long expectedRevision;
    private final String title;
    private final String description;
    private final Map<Key, Object> valuesToSet;
    private final Set<Key> valuesToRemove;
    private final ReplayMetadata replacement;

    private MetadataMutation(Builder builder) {
        this.replayId = builder.replayId;
        this.expectedRevision = builder.expectedRevision;
        this.title = builder.title;
        this.description = builder.description;
        this.valuesToSet = Collections.unmodifiableMap(new LinkedHashMap<>(builder.valuesToSet));
        this.valuesToRemove = Collections.unmodifiableSet(new LinkedHashSet<>(builder.valuesToRemove));
        this.replacement = builder.replacement;
    }

    /**
     * Starts a mutation for one replay.
     *
     * @param replayId replay identifier
     * @return mutation builder
     */
    public static Builder builder(ReplayId replayId) {
        return new Builder(replayId);
    }

    /**
     * Returns the replay identifier.
     *
     * @return replay identifier
     */
    public ReplayId replayId() {
        return replayId;
    }

    /**
     * Returns the revision the caller observed before building the mutation.
     *
     * @return expected current revision
     */
    public long expectedRevision() {
        return expectedRevision;
    }

    /**
     * Returns an optional title replacement.
     *
     * @return title change
     */
    public Optional<String> title() {
        return Optional.ofNullable(title);
    }

    /**
     * Returns an optional description replacement.
     *
     * @return description change
     */
    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    /**
     * Returns custom values to set.
     *
     * @return immutable set-value map
     */
    public Map<Key, Object> valuesToSet() {
        return valuesToSet;
    }

    /**
     * Returns custom keys to remove.
     *
     * @return immutable removal set
     */
    public Set<Key> valuesToRemove() {
        return valuesToRemove;
    }

    /**
     * Returns the full snapshot replacement, if this is a replacement mutation.
     *
     * @return replacement snapshot
     */
    public Optional<ReplayMetadata> replacement() {
        return Optional.ofNullable(replacement);
    }

    /** Builder for an immutable metadata mutation. */
    public static final class Builder {
        private final ReplayId replayId;
        private Long expectedRevision;
        private String title;
        private String description;
        private final Map<Key, Object> valuesToSet = new LinkedHashMap<>();
        private final Set<Key> valuesToRemove = new LinkedHashSet<>();
        private ReplayMetadata replacement;

        private Builder(ReplayId replayId) {
            this.replayId = Objects.requireNonNull(replayId, "replayId");
        }

        /**
         * Sets the revision expected by the eventual atomic service operation.
         *
         * @param revision expected current revision
         * @return this builder
         */
        public Builder expectedRevision(long revision) {
            if (revision < 0) {
                throw new IllegalArgumentException("expectedRevision must not be negative");
            }
            this.expectedRevision = revision;
            return this;
        }

        /**
         * Sets a title replacement.
         *
         * @param title non-blank title
         * @return this builder
         */
        public Builder title(String title) {
            ensurePatchMode();
            Objects.requireNonNull(title, "title");
            if (title.isBlank()) {
                throw new IllegalArgumentException("title must not be blank");
            }
            this.title = title;
            return this;
        }

        /**
         * Sets a description replacement.
         *
         * @param description non-null description, possibly empty
         * @return this builder
         */
        public Builder description(String description) {
            ensurePatchMode();
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        /**
         * Sets one typed custom value.
         *
         * @param key typed metadata key
         * @param value non-null value
         * @param <T> value type
         * @return this builder
         */
        public <T> Builder put(ReplayMetadataKey<T> key, T value) {
            ensurePatchMode();
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            if (!key.type().getRawType().isInstance(value)) {
                throw new IllegalArgumentException("value does not match metadata key type");
            }
            if (valuesToSet.containsKey(key.key()) || valuesToRemove.contains(key.key())) {
                throw new IllegalArgumentException("metadata key already has a mutation");
            }
            valuesToSet.put(key.key(), value);
            return this;
        }

        /**
         * Removes one custom value.
         *
         * @param key metadata key to remove
         * @return this builder
         */
        public Builder remove(ReplayMetadataKey<?> key) {
            ensurePatchMode();
            Objects.requireNonNull(key, "key");
            if (valuesToSet.containsKey(key.key()) || !valuesToRemove.add(key.key())) {
                throw new IllegalArgumentException("metadata key already has a mutation");
            }
            return this;
        }

        /**
         * Replaces the complete metadata snapshot.
         *
         * @param replacement replacement snapshot for the same replay
         * @return this builder
         */
        public Builder replaceWith(ReplayMetadata replacement) {
            Objects.requireNonNull(replacement, "replacement");
            if (title != null || description != null
                    || !valuesToSet.isEmpty() || !valuesToRemove.isEmpty()) {
                throw new IllegalStateException("replacement cannot be combined with patch changes");
            }
            if (!replayId.equals(replacement.replayId())) {
                throw new IllegalArgumentException("replacement replayId must match mutation replayId");
            }
            this.replacement = replacement;
            return this;
        }

        /**
         * Builds the immutable mutation.
         *
         * @return mutation snapshot
         */
        public MetadataMutation build() {
            if (expectedRevision == null) {
                throw new NullPointerException("expectedRevision");
            }
            if (replacement == null && title == null && description == null
                    && valuesToSet.isEmpty() && valuesToRemove.isEmpty()) {
                throw new IllegalArgumentException("mutation must contain a change");
            }
            return new MetadataMutation(this);
        }

        private void ensurePatchMode() {
            if (replacement != null) {
                throw new IllegalStateException("replacement cannot be combined with patch changes");
            }
        }
    }
}
