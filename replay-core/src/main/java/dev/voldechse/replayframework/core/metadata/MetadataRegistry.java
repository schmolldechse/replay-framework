package dev.voldechse.replayframework.core.metadata;

import dev.voldechse.replayframework.api.metadata.ReplayMetadataKey;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.kyori.adventure.key.Key;

/**
 * Thread-safe registry of the metadata definitions known to the runtime.
 *
 * <p>A definition is immutable from the registry's perspective. Re-registering the same
 * definition is idempotent, while replacing a codec or type is rejected so persisted JSON cannot
 * be decoded with a different contract later in the process.</p>
 */
public final class MetadataRegistry {
    private final ConcurrentMap<Key, RegisteredKey<?>> definitions = new ConcurrentHashMap<>();

    /**
     * Registers one metadata key or verifies an identical existing definition.
     *
     * @param key metadata definition
     * @param <T> metadata value type
     * @throws IllegalArgumentException if the canonical key has a conflicting definition
     */
    public <T> void register(ReplayMetadataKey<T> key) {
        Objects.requireNonNull(key, "key");
        RegisteredKey<T> candidate = new RegisteredKey<>(key);
        definitions.compute(key.key(), (canonicalKey, existing) -> {
            if (existing == null) {
                return candidate;
            }
            if (compatible(existing.definition(), key)) {
                return existing;
            }
            throw new IllegalArgumentException(
                    "metadata key conflicts with existing definition: " + canonicalKey.asString());
        });
    }

    /**
     * Finds a definition by its canonical namespaced key.
     *
     * @param key canonical metadata key
     * @return registered definition when present
     */
    public Optional<RegisteredKey<?>> find(Key key) {
        return Optional.ofNullable(definitions.get(Objects.requireNonNull(key, "key")));
    }

    /**
     * Resolves a canonical key and fails when no definition is registered.
     *
     * @param key canonical metadata key
     * @return registered definition
     * @throws IllegalStateException when the key is not registered
     */
    public RegisteredKey<?> require(Key key) {
        Key canonicalKey = Objects.requireNonNull(key, "key");
        return find(canonicalKey).orElseThrow(() ->
                new IllegalStateException(
                        "metadata key is not registered: " + canonicalKey.asString()));
    }

    /**
     * Resolves a typed definition and verifies that the supplied contract matches registration.
     *
     * @param key typed metadata definition
     * @param <T> metadata value type
     * @return registered definition with the same type
     * @throws IllegalArgumentException when the supplied definition conflicts with registration
     */
    @SuppressWarnings("unchecked")
    public <T> RegisteredKey<T> require(ReplayMetadataKey<T> key) {
        ReplayMetadataKey<T> requested = Objects.requireNonNull(key, "key");
        RegisteredKey<?> registered = require(requested.key());
        if (!compatible(registered.definition(), requested)) {
            throw new IllegalArgumentException(
                    "metadata key conflicts with existing definition: " + requested.key().asString());
        }
        return (RegisteredKey<T>) registered;
    }

    private static boolean compatible(
            ReplayMetadataKey<?> existing,
            ReplayMetadataKey<?> candidate) {
        return existing.type().equals(candidate.type())
                && existing.queryCapabilities().operations()
                        .equals(candidate.queryCapabilities().operations())
                && existing.codec() == candidate.codec();
    }

    /** Immutable handle used by core and database adapters without exposing the registry map. */
    public record RegisteredKey<T>(ReplayMetadataKey<T> definition) {
        public RegisteredKey {
            Objects.requireNonNull(definition, "definition");
        }
    }
}
