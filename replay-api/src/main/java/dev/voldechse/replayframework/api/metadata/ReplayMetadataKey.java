package dev.voldechse.replayframework.api.metadata;

import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import java.util.Objects;
import net.kyori.adventure.key.Key;

/**
 * Namespaced, typed definition of one custom replay metadata field.
 *
 * @param <T> Java value type represented by the field
 */
public interface ReplayMetadataKey<T> {
    /**
     * Returns the canonical namespaced field key.
     *
     * @return metadata key
     */
    Key key();

    /**
     * Returns the Gson type token used by the metadata registry.
     *
     * @return value type token
     */
    TypeToken<T> type();

    /**
     * Returns the Gson codec owned by this metadata key.
     *
     * <p>The codec is part of the key contract so registration, revision persistence and query
     * value binding use the same type-specific JSON representation. The supplied adapter is
     * retained as-is; callers may choose {@link TypeAdapter#nullSafe()} when null values are part
     * of their own value contract.</p>
     *
     * @return type-specific Gson codec
     */
    TypeAdapter<T> codec();

    /**
     * Returns the operations supported for this field.
     *
     * @return immutable query capabilities
     */
    QueryCapabilities queryCapabilities();

    /**
     * Creates an immutable standard metadata key.
     *
     * @param key namespaced field key
     * @param type Gson value type
     * @param codec type-specific Gson codec
     * @param queryCapabilities supported query operations
     * @param <T> Java value type
     * @return metadata key definition
     */
    static <T> ReplayMetadataKey<T> of(
            Key key,
            TypeToken<T> type,
            TypeAdapter<T> codec,
            QueryCapabilities queryCapabilities) {
        return new DefaultReplayMetadataKey<>(key, type, codec, queryCapabilities);
    }
}

final class DefaultReplayMetadataKey<T> implements ReplayMetadataKey<T> {
    private final Key key;
    private final TypeToken<T> type;
    private final TypeAdapter<T> codec;
    private final QueryCapabilities queryCapabilities;

    DefaultReplayMetadataKey(
            Key key,
            TypeToken<T> type,
            TypeAdapter<T> codec,
            QueryCapabilities queryCapabilities) {
        this.key = Objects.requireNonNull(key, "key");
        this.type = Objects.requireNonNull(type, "type");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.queryCapabilities = Objects.requireNonNull(queryCapabilities, "queryCapabilities");
    }

    @Override
    public Key key() {
        return key;
    }

    @Override
    public TypeToken<T> type() {
        return type;
    }

    @Override
    public TypeAdapter<T> codec() {
        return codec;
    }

    @Override
    public QueryCapabilities queryCapabilities() {
        return queryCapabilities;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReplayMetadataKey<?> otherKey && key.equals(otherKey.key());
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key.asString();
    }
}
