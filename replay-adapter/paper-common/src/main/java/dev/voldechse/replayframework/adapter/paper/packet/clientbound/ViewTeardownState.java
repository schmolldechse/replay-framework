package dev.voldechse.replayframework.adapter.paper.packet.clientbound;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Packet-derived state of one connection's view teardown between its player's
 * death and respawn.
 *
 * <p>The server removes a dead player from the level and then unloads every
 * chunk and entity from that connection's view. Those unloads describe the
 * recipient's view, not the scene: after the respawn the same chunks and
 * entities are sent again. An adapter that tracks this state withholds the
 * unloads and only publishes the ones that the reloaded view no longer
 * covers. The default implementation tracks nothing, so adapters without
 * teardown handling keep recording every unload as observed.</p>
 */
public interface ViewTeardownState {

    /** Tracks nothing and never withholds an unload. */
    ViewTeardownState NONE = new ViewTeardownState() {
    };

    /** Records that the connection's own player died in the given world. */
    default void playerDied(UUID connectionId, String world) {
    }

    /**
     * Records the respawn that ends a teardown. A respawn into another world
     * releases every withheld unload, because that view is not rebuilt.
     */
    default void playerRespawned(UUID connectionId, long serverTick, boolean sameWorld) {
    }

    /**
     * Claims a chunk unload observed on the connection. A dead player cannot
     * move its view, so its first chunk unload starts the teardown.
     *
     * @return {@code true} when the unload belongs to the teardown and is withheld
     */
    default boolean withholdChunkUnload(UUID connectionId, int chunkX, int chunkZ) {
        return false;
    }

    /** Whether unloads observed on the connection currently belong to its teardown. */
    default boolean tearingDown(UUID connectionId) {
        return false;
    }

    /**
     * Withholds an entity removal that belongs to the teardown.
     *
     * @param chunk the entity's last known chunk, or empty when no position is known
     */
    default void withholdEntityRemoval(
            UUID connectionId, int entityId, Optional<ChunkCoordinate> chunk) {
    }

    /** Records that the connection received a chunk again, replacing it in place. */
    default void chunkLoaded(UUID connectionId, int chunkX, int chunkZ, long serverTick) {
    }

    /** Records that the connection received an entity again, replacing it in place. */
    default void entityAdded(UUID connectionId, int entityId) {
    }

    /** Records the center of the connection's chunk view. */
    default void viewCenter(UUID connectionId, int chunkX, int chunkZ) {
    }

    /** Records the radius of the connection's chunk view. */
    default void viewRadius(UUID connectionId, int radius) {
    }

    /** Returns and clears the withheld unloads that must now be published. */
    default Unloads drainDue(UUID connectionId, long serverTick) {
        return Unloads.NONE;
    }

    /** One chunk column. */
    record ChunkCoordinate(int x, int z) {

        /** Returns the chunk containing a block-space position. */
        public static ChunkCoordinate containing(double x, double z) {
            return new ChunkCoordinate(
                    (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        }
    }

    /**
     * Withheld unloads that the rebuilt view no longer covers.
     *
     * @param world world the unloads were observed in, when known
     * @param chunks chunk columns to unload
     * @param entityIds protocol entity IDs to remove
     */
    record Unloads(
            Optional<String> world, List<ChunkCoordinate> chunks, List<Integer> entityIds) {

        public static final Unloads NONE = new Unloads(Optional.empty(), List.of(), List.of());

        public Unloads {
            Objects.requireNonNull(world, "world");
            chunks = List.copyOf(chunks);
            entityIds = List.copyOf(entityIds);
        }

        public boolean isEmpty() {
            return chunks.isEmpty() && entityIds.isEmpty();
        }
    }
}
