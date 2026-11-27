package dev.voldechse.replayframework.adapter.paper.argus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Merges what several eyes of one world see into a single scene.
 *
 * <p>Chunks and entities are reference-counted by the eyes that hold them:
 * the scene gains one when its first eye does and loses it when its last eye
 * does. Broadcast packets are recognized by object identity, because the
 * server hands the same object to every recipient. No payload is compared.</p>
 *
 * <p>Not thread-safe; used on the server thread only.</p>
 */
public final class ArgusScene {

    private final Map<Long, Set<UUID>> chunkHolders = new HashMap<>();
    private final Map<Integer, Set<UUID>> entityHolders = new HashMap<>();
    private final Map<Object, UUID> broadcastsThisTick = new IdentityHashMap<>();
    private long broadcastTick = -1L;

    /**
     * Records a broadcast packet seen by an eye.
     *
     * @return {@code true} when the scene has not seen this packet yet. A
     *         packet the same eye receives again is a real repetition and is
     *         also new; only the copy of another eye is a duplicate.
     */
    public boolean broadcast(UUID eye, Object packet, long serverTick) {
        Objects.requireNonNull(eye, "eye");
        Objects.requireNonNull(packet, "packet");
        if (serverTick != broadcastTick) {
            broadcastsThisTick.clear();
            broadcastTick = serverTick;
        }
        UUID first = broadcastsThisTick.putIfAbsent(packet, eye);
        return first == null || first.equals(eye);
    }

    /** @return {@code true} when the chunk is new to the scene or resent to its holder */
    public boolean chunkGained(UUID eye, int chunkX, int chunkZ) {
        return gain(chunkHolders, chunkKey(chunkX, chunkZ), eye);
    }

    /** @return {@code true} when no eye holds the chunk anymore */
    public boolean chunkLost(UUID eye, int chunkX, int chunkZ) {
        return lose(chunkHolders, chunkKey(chunkX, chunkZ), eye);
    }

    /** @return {@code true} when the entity is new to the scene or resent to its holder */
    public boolean entityGained(UUID eye, int entityId) {
        return gain(entityHolders, entityId, eye);
    }

    /** @return the entities no eye holds anymore, in the given order */
    public int[] entitiesLost(UUID eye, int[] entityIds) {
        Objects.requireNonNull(entityIds, "entityIds");
        return Arrays.stream(entityIds)
                .filter(entityId -> lose(entityHolders, entityId, eye))
                .toArray();
    }

    /** Removes an eye and returns everything only it was holding. */
    public Released release(UUID eye) {
        Objects.requireNonNull(eye, "eye");
        List<ChunkPosition> chunks = new ArrayList<>();
        for (Long key : releaseAll(chunkHolders, eye)) {
            chunks.add(new ChunkPosition((int) (key >> 32), (int) (long) key));
        }
        int[] entities = releaseAll(entityHolders, eye).stream()
                .mapToInt(Integer::intValue)
                .sorted()
                .toArray();
        return new Released(chunks, entities);
    }

    private static <K> boolean gain(Map<K, Set<UUID>> holders, K key, UUID eye) {
        Objects.requireNonNull(eye, "eye");
        Set<UUID> eyes = holders.computeIfAbsent(key, ignored -> new HashSet<>());
        boolean first = eyes.isEmpty();
        boolean resent = !eyes.add(eye);
        return first || resent;
    }

    private static <K> boolean lose(Map<K, Set<UUID>> holders, K key, UUID eye) {
        Objects.requireNonNull(eye, "eye");
        Set<UUID> eyes = holders.get(key);
        if (eyes == null || !eyes.remove(eye) || !eyes.isEmpty()) {
            return false;
        }
        holders.remove(key);
        return true;
    }

    private static <K> List<K> releaseAll(Map<K, Set<UUID>> holders, UUID eye) {
        List<K> released = new ArrayList<>();
        holders.entrySet().removeIf(entry -> {
            if (entry.getValue().remove(eye) && entry.getValue().isEmpty()) {
                released.add(entry.getKey());
                return true;
            }
            return false;
        });
        return released;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Chunk coordinates. */
    public record ChunkPosition(int x, int z) {}

    /**
     * What left the scene together with an eye.
     *
     * @param chunks chunks only that eye was holding
     * @param entityIds protocol entity IDs in ascending order
     */
    public record Released(List<ChunkPosition> chunks, int[] entityIds) {
        public Released {
            chunks = List.copyOf(chunks);
            entityIds = entityIds.clone();
        }
    }
}
