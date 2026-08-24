package dev.voldechse.replayframework.core.metadata;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.inject.Inject;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.metadata.MetadataMutation;
import dev.voldechse.replayframework.api.metadata.MetadataRevision;
import dev.voldechse.replayframework.api.metadata.MetadataRevisionOperation;
import dev.voldechse.replayframework.api.metadata.ReplayMetadata;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataKey;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataService;
import dev.voldechse.replayframework.core.port.MetadataRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.key.Key;

/**
 * Core implementation of the public typed metadata service.
 *
 * <p>The service owns JSON/value conversion while the repository owns the atomic database
 * transaction. No persistence entity crosses this boundary.</p>
 */
public final class DefaultReplayMetadataService implements ReplayMetadataService {
    private final MetadataRepository repository;
    private final MetadataRegistry registry;

    /**
     * Creates a metadata service over the supplied internal repository port.
     *
     * @param repository internal metadata repository
     * @param registry runtime metadata definition registry
     */
    @Inject
    public DefaultReplayMetadataService(
            MetadataRepository repository,
            MetadataRegistry registry) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public <T> void register(ReplayMetadataKey<T> key) {
        registry.register(key);
    }

    @Override
    public CompletionStage<ReplayMetadata> get(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return repository.current(replayId)
                .thenApply(current -> requireCurrent(current, replayId));
    }

    @Override
    public CompletionStage<ReplayMetadata> apply(MetadataMutation mutation) {
        Objects.requireNonNull(mutation, "mutation");
        return repository.current(mutation.replayId())
                .thenCompose(current -> {
                    ReplayMetadata observed = requireCurrent(current, mutation.replayId());
                    if (observed.revision() != mutation.expectedRevision()) {
                        return CompletableFuture.failedFuture(
                                new MetadataRepository.MetadataRevisionConflictException(
                                        mutation.replayId(), mutation.expectedRevision()));
                    }

                    ReplayMetadata next = buildNextSnapshot(mutation, observed);
                    if (sameSnapshot(observed, next)) {
                        return CompletableFuture.completedFuture(toPortSnapshot(observed));
                    }

                    MetadataRepository.MetadataWrite write = new MetadataRepository.MetadataWrite(
                            mutation.replayId(),
                            mutation.expectedRevision(),
                            toPortSnapshot(next),
                            operation(mutation),
                            Instant.now());
                    return repository.apply(write);
                })
                .thenApply(this::decodeSnapshot);
    }

    @Override
    public CompletionStage<List<MetadataRevision>> history(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return repository.history(replayId).thenApply(rows -> {
            List<MetadataRevision> result = new ArrayList<>(rows.size());
            long expectedRevision = 1L;
            for (MetadataRepository.MetadataRevisionRow row : rows) {
                if (row.revision() != expectedRevision) {
                    throw new IllegalStateException(
                            "metadata history has a revision gap for " + replayId);
                }
                ReplayMetadata snapshot = decodeSnapshot(row.snapshot());
                if (snapshot.revision() != row.revision()) {
                    throw new IllegalStateException(
                            "metadata history snapshot revision mismatch for " + replayId);
                }
                result.add(new MetadataRevision(
                        row.revision(), row.timestamp(), row.operation(), snapshot));
                expectedRevision++;
            }
            return List.copyOf(result);
        });
    }

    private ReplayMetadata requireCurrent(
            Optional<MetadataRepository.MetadataSnapshot> current,
            ReplayId replayId) {
        return current
                .map(this::decodeSnapshot)
                .orElseThrow(() -> new MetadataRepository.MetadataNotFoundException(replayId));
    }

    private ReplayMetadata buildNextSnapshot(
            MetadataMutation mutation,
            ReplayMetadata current) {
        long nextRevision = mutation.expectedRevision() + 1L;
        if (mutation.replacement().isPresent()) {
            ReplayMetadata replacement = mutation.replacement().orElseThrow();
            validateValues(replacement.values());
            return new ReplayMetadata(
                    mutation.replayId(),
                    replacement.title(),
                    replacement.description(),
                    nextRevision,
                    replacement.values());
        }

        Map<Key, Object> values = new LinkedHashMap<>(current.values());
        mutation.valuesToSet().forEach((key, value) -> {
            validateValue(key, value);
            values.put(key, value);
        });
        mutation.valuesToRemove().forEach(key -> {
            registry.require(key);
            values.remove(key);
        });

        return new ReplayMetadata(
                mutation.replayId(),
                mutation.title().orElse(current.title()),
                mutation.description().orElse(current.description()),
                nextRevision,
                values);
    }

    private MetadataRepository.MetadataSnapshot toPortSnapshot(ReplayMetadata metadata) {
        return new MetadataRepository.MetadataSnapshot(
                metadata.replayId(),
                metadata.title(),
                metadata.description(),
                metadata.revision(),
                encodeValues(metadata.values()));
    }

    private JsonObject encodeValues(Map<Key, Object> values) {
        JsonObject encoded = new JsonObject();
        values.forEach((key, value) -> {
            encoded.add(key.asString(), encodeValue(key, value));
        });
        return encoded;
    }

    private JsonElement encodeValue(Key key, Object value) {
        MetadataRegistry.RegisteredKey<?> registered = registry.require(key);
        validateValue(registered, key, value);
        try {
            return toJsonTree(registered.definition().codec(), value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "cannot encode metadata key: " + key.asString(), exception);
        }
    }

    private ReplayMetadata decodeSnapshot(MetadataRepository.MetadataSnapshot snapshot) {
        Map<Key, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : snapshot.values().entrySet()) {
            Key key;
            try {
                key = Key.key(entry.getKey());
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "invalid metadata key: " + entry.getKey(), exception);
            }
            MetadataRegistry.RegisteredKey<?> registered = registry.require(key);
            try {
                Object decoded = fromJsonTree(registered.definition().codec(), entry.getValue());
                validateValue(registered, key, decoded);
                values.put(key, decoded);
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "cannot decode metadata key: " + key.asString(), exception);
            }
        }
        return new ReplayMetadata(
                snapshot.replayId(),
                snapshot.title(),
                snapshot.description(),
                snapshot.revision(),
                values);
    }

    private void validateValues(Map<Key, Object> values) {
        values.forEach(this::validateValue);
    }

    private void validateValue(Key key, Object value) {
        MetadataRegistry.RegisteredKey<?> registered = registry.require(key);
        validateValue(registered, key, value);
    }

    private static void validateValue(
            MetadataRegistry.RegisteredKey<?> registered,
            Key key,
            Object value) {
        Objects.requireNonNull(value, "metadata value");
        if (!registered.definition().type().getRawType().isInstance(value)) {
            throw new IllegalArgumentException(
                    "metadata value does not match key type: " + key.asString());
        }
    }

    private static boolean sameSnapshot(ReplayMetadata left, ReplayMetadata right) {
        return left.title().equals(right.title())
                && left.description().equals(right.description())
                && left.values().equals(right.values());
    }

    private static MetadataRevisionOperation operation(MetadataMutation mutation) {
        if (mutation.replacement().isPresent()) {
            return MetadataRevisionOperation.REPLACE;
        }
        if (mutation.title().isEmpty()
                && mutation.description().isEmpty()
                && mutation.valuesToSet().isEmpty()
                && !mutation.valuesToRemove().isEmpty()) {
            return MetadataRevisionOperation.REMOVE;
        }
        return MetadataRevisionOperation.SET;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JsonElement toJsonTree(TypeAdapter codec, Object value) {
        JsonElement element = codec.toJsonTree(value);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("metadata codec returned JSON null");
        }
        return element.deepCopy();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object fromJsonTree(TypeAdapter codec, JsonElement element) {
        Objects.requireNonNull(element, "metadata JSON value");
        return Objects.requireNonNull(codec.fromJsonTree(element), "metadata codec returned null");
    }
}
