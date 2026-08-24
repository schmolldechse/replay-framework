package dev.voldechse.replayframework.api.metadata;

import dev.voldechse.replayframework.api.id.ReplayId;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Public facade for typed metadata registration and revisioned mutations.
 */
public interface ReplayMetadataService {
    /**
     * Registers one namespaced typed metadata key.
     *
     * @param key metadata key definition
     * @param <T> key value type
     * @throws IllegalArgumentException when the namespace is already registered
     *                                  with a conflicting type or capability set
     */
    <T> void register(ReplayMetadataKey<T> key);

    /**
     * Loads the current metadata snapshot asynchronously.
     *
     * @param replayId replay identifier
     * @return current snapshot
     */
    CompletionStage<ReplayMetadata> get(ReplayId replayId);

    /**
     * Applies one optimistic-locking mutation atomically.
     *
     * @param mutation immutable metadata mutation
     * @return resulting current snapshot
     */
    CompletionStage<ReplayMetadata> apply(MetadataMutation mutation);

    /**
     * Loads the complete immutable metadata revision history.
     *
     * @param replayId replay identifier
     * @return revisions in ascending revision order
     */
    CompletionStage<List<MetadataRevision>> history(ReplayId replayId);

}
