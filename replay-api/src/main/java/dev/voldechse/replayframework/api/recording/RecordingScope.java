package dev.voldechse.replayframework.api.recording;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.kyori.adventure.key.Key;

/**
 * Immutable criteria describing which loaded world areas a recording covers.
 *
 * <p>An empty world and region selection deliberately remains valid. The core
 * resolves it later as all currently loaded worlds; this API object never
 * touches Paper worlds or chunks.</p>
 */
public final class RecordingScope {
    private final Set<Key> worlds;
    private final List<CuboidRegion> regions;
    private final ChunkLoadingPolicy chunkLoadingPolicy;

    private RecordingScope(Builder builder) {
        this.worlds = Set.copyOf(builder.worlds);
        this.regions = List.copyOf(builder.regions);
        this.chunkLoadingPolicy = builder.chunkLoadingPolicy;
    }

    /**
     * Creates a builder with an empty scope and {@link ChunkLoadingPolicy#LOADED_ONLY}.
     *
     * @return a new scope builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the explicitly selected worlds.
     *
     * @return immutable world-key set
     */
    public Set<Key> worlds() {
        return worlds;
    }

    /**
     * Returns the explicitly selected regions in builder insertion order.
     *
     * @return immutable region list
     */
    public List<CuboidRegion> regions() {
        return regions;
    }

    /**
     * Returns the chunk loading policy.
     *
     * @return chunk loading policy
     */
    public ChunkLoadingPolicy chunkLoadingPolicy() {
        return chunkLoadingPolicy;
    }

    /** Builder for an immutable {@link RecordingScope}. */
    public static final class Builder {
        private final Set<Key> worlds = new LinkedHashSet<>();
        private final List<CuboidRegion> regions = new ArrayList<>();
        private ChunkLoadingPolicy chunkLoadingPolicy = ChunkLoadingPolicy.LOADED_ONLY;

        private Builder() {
        }

        /**
         * Adds a world criterion. Repeated values are coalesced by set semantics.
         *
         * @param world world key
         * @return this builder
         */
        public Builder addWorld(Key world) {
            worlds.add(Objects.requireNonNull(world, "world"));
            return this;
        }

        /**
         * Adds a region criterion.
         *
         * @param region region to include
         * @return this builder
         */
        public Builder addRegion(CuboidRegion region) {
            regions.add(Objects.requireNonNull(region, "region"));
            return this;
        }

        /**
         * Selects how chunks in the scope may be loaded.
         *
         * @param chunkLoadingPolicy requested policy
         * @return this builder
         */
        public Builder chunkLoadingPolicy(ChunkLoadingPolicy chunkLoadingPolicy) {
            this.chunkLoadingPolicy = Objects.requireNonNull(chunkLoadingPolicy, "chunkLoadingPolicy");
            return this;
        }

        /**
         * Builds an immutable scope snapshot.
         *
         * @return immutable scope
         * @throws IllegalArgumentException if preloading has no bounded region
         */
        public RecordingScope build() {
            if (chunkLoadingPolicy == ChunkLoadingPolicy.PRELOAD_SCOPE && regions.isEmpty()) {
                throw new IllegalArgumentException(
                        "PRELOAD_SCOPE requires at least one bounded region");
            }
            return new RecordingScope(this);
        }
    }
}
