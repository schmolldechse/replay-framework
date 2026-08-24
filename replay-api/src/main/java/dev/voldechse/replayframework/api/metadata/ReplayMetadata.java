package dev.voldechse.replayframework.api.metadata;

import dev.voldechse.replayframework.api.id.ReplayId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.key.Key;

/**
 * Immutable current metadata snapshot of one replay.
 */
public final class ReplayMetadata {
    private final ReplayId replayId;
    private final String title;
    private final String description;
    private final long revision;
    private final Map<Key, Object> values;

    /**
     * Creates an immutable metadata snapshot.
     *
     * @param replayId replay identifier
     * @param title non-blank replay title
     * @param description non-null replay description
     * @param revision snapshot revision, starting at zero
     * @param values custom metadata values by namespaced key
     */
    public ReplayMetadata(
            ReplayId replayId,
            String title,
            String description,
            long revision,
            Map<Key, ?> values) {
        this.replayId = Objects.requireNonNull(replayId, "replayId");
        this.title = Objects.requireNonNull(title, "title");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.description = Objects.requireNonNull(description, "description");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        Objects.requireNonNull(values, "values");
        Map<Key, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "values key"),
                Objects.requireNonNull(value, "values value")));
        this.revision = revision;
        this.values = Collections.unmodifiableMap(copy);
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
     * Returns the non-blank title.
     *
     * @return title
     */
    public String title() {
        return title;
    }

    /**
     * Returns the description.
     *
     * @return description
     */
    public String description() {
        return description;
    }

    /**
     * Returns the snapshot revision.
     *
     * @return revision
     */
    public long revision() {
        return revision;
    }

    /**
     * Returns custom values through an immutable map.
     *
     * @return immutable custom values
     */
    public Map<Key, Object> values() {
        return values;
    }

    /**
     * Returns all custom metadata keys.
     *
     * @return immutable key set
     */
    public Set<Key> keys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    /**
     * Reads one custom value through its typed key definition.
     *
     * @param key typed metadata key
     * @param <T> value type
     * @return value when present
     */
    public <T> Optional<T> value(ReplayMetadataKey<T> key) {
        Objects.requireNonNull(key, "key");
        Object value = values.get(key.key());
        if (value == null) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        T typedValue = (T) key.type().getRawType().cast(value);
        return Optional.of(typedValue);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReplayMetadata that)) {
            return false;
        }
        return revision == that.revision
                && replayId.equals(that.replayId)
                && title.equals(that.title)
                && description.equals(that.description)
                && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(replayId, title, description, revision, values);
    }
}
