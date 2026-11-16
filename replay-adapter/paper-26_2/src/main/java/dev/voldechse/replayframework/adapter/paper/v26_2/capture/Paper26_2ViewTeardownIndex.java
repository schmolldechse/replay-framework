package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import dev.voldechse.replayframework.adapter.paper.packet.ViewTeardownState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Withholds the chunk and entity unloads Paper 26.2 sends to a dead player's
 * connection and releases only those its rebuilt view does not cover.
 *
 * <p>Paper removes a dead player from the level once its death animation
 * ends, or at the respawn when that comes first. Either way the connection
 * first receives {@code forget_level_chunk} for every loaded chunk and then
 * {@code remove_entities} for every tracked entity. After the respawn the
 * chunks in range are sent again, each followed by its tracked entities.</p>
 */
final class Paper26_2ViewTeardownIndex implements ViewTeardownState {

    /**
     * Ticks an entity may take to reappear after its chunk was sent again.
     * Paper adds tracked entities on the tracker pass after the chunk reaches
     * the player; one second leaves room for a delayed flush.
     */
    static final long ENTITY_RESEND_GRACE_TICKS = 20L;

    /** Paper keeps chunks loaded for the client one column beyond the announced radius. */
    private static final int VIEW_MARGIN_CHUNKS = 1;

    private static final long NOT_SCHEDULED = Long.MAX_VALUE;

    private final Map<UUID, View> views = new HashMap<>();

    @Override
    public synchronized void playerDied(UUID connectionId, String world) {
        if (connectionId == null) {
            return;
        }
        View view = views.computeIfAbsent(connectionId, ignored -> new View());
        view.dead = true;
        if (world != null) {
            view.world = world;
        }
    }

    @Override
    public synchronized void playerRespawned(
            UUID connectionId, long serverTick, boolean sameWorld) {
        View view = connectionId == null ? null : views.get(connectionId);
        if (view == null) {
            return;
        }
        view.dead = false;
        view.tearingDown = false;
        // The known center is where the player died. Nothing is released
        // against it; the respawn announces the center of the rebuilt view.
        view.center = null;
        if (!sameWorld) {
            view.releasedChunks.addAll(view.chunks);
            view.releasedEntities.addAll(view.entities.keySet());
            view.chunks.clear();
            view.entities.clear();
            return;
        }
        // An entity without a withheld chunk has no reload to wait for.
        for (WithheldEntity entity : view.entities.values()) {
            if (entity.chunk == null || !view.chunks.contains(entity.chunk)) {
                entity.dueTick = serverTick + ENTITY_RESEND_GRACE_TICKS;
            }
        }
    }

    @Override
    public synchronized boolean withholdChunkUnload(UUID connectionId, int chunkX, int chunkZ) {
        View view = connectionId == null ? null : views.get(connectionId);
        if (view == null || !view.dead) {
            return false;
        }
        view.tearingDown = true;
        view.chunks.add(new ChunkCoordinate(chunkX, chunkZ));
        return true;
    }

    @Override
    public synchronized boolean tearingDown(UUID connectionId) {
        View view = connectionId == null ? null : views.get(connectionId);
        return view != null && view.tearingDown;
    }

    @Override
    public synchronized void withholdEntityRemoval(
            UUID connectionId, int entityId, Optional<ChunkCoordinate> chunk) {
        View view = connectionId == null ? null : views.get(connectionId);
        if (view == null || !view.tearingDown) {
            return;
        }
        view.entities.put(entityId, new WithheldEntity(chunk.orElse(null)));
    }

    @Override
    public synchronized void chunkLoaded(
            UUID connectionId, int chunkX, int chunkZ, long serverTick) {
        View view = connectionId == null ? null : views.get(connectionId);
        if (view == null || view.tearingDown) {
            return;
        }
        ChunkCoordinate chunk = new ChunkCoordinate(chunkX, chunkZ);
        if (!view.chunks.remove(chunk)) {
            return;
        }
        for (WithheldEntity entity : view.entities.values()) {
            if (chunk.equals(entity.chunk) && entity.dueTick == NOT_SCHEDULED) {
                entity.dueTick = serverTick + ENTITY_RESEND_GRACE_TICKS;
            }
        }
    }

    @Override
    public synchronized void entityAdded(UUID connectionId, int entityId) {
        View view = connectionId == null ? null : views.get(connectionId);
        if (view != null && !view.tearingDown) {
            view.entities.remove(entityId);
        }
    }

    @Override
    public synchronized void viewCenter(UUID connectionId, int chunkX, int chunkZ) {
        if (connectionId == null) {
            return;
        }
        View view = views.computeIfAbsent(connectionId, ignored -> new View());
        view.center = new ChunkCoordinate(chunkX, chunkZ);
        releaseOutOfView(view);
    }

    @Override
    public synchronized void viewRadius(UUID connectionId, int radius) {
        if (connectionId == null) {
            return;
        }
        View view = views.computeIfAbsent(connectionId, ignored -> new View());
        view.radius = radius;
        releaseOutOfView(view);
    }

    @Override
    public synchronized Unloads drainDue(UUID connectionId, long serverTick) {
        View view = connectionId == null ? null : views.get(connectionId);
        if (view == null) {
            return Unloads.NONE;
        }
        if (!view.dead && !view.tearingDown) {
            Iterator<Map.Entry<Integer, WithheldEntity>> entities =
                    view.entities.entrySet().iterator();
            while (entities.hasNext()) {
                Map.Entry<Integer, WithheldEntity> entry = entities.next();
                if (entry.getValue().dueTick <= serverTick) {
                    view.releasedEntities.add(entry.getKey());
                    entities.remove();
                }
            }
        }
        if (view.releasedChunks.isEmpty() && view.releasedEntities.isEmpty()) {
            return Unloads.NONE;
        }
        Unloads unloads = new Unloads(
                Optional.ofNullable(view.world), view.releasedChunks, view.releasedEntities);
        view.releasedChunks.clear();
        view.releasedEntities.clear();
        return unloads;
    }

    synchronized void removeConnection(UUID connectionId) {
        if (connectionId != null) {
            views.remove(connectionId);
        }
    }

    synchronized void clear() {
        views.clear();
    }

    /** Releases withheld chunks, and their entities, that the rebuilt view no longer covers. */
    private static void releaseOutOfView(View view) {
        if (view.dead || view.tearingDown || view.center == null || view.radius < 0
                || view.chunks.isEmpty()) {
            return;
        }
        int reach = view.radius + VIEW_MARGIN_CHUNKS;
        Iterator<ChunkCoordinate> chunks = view.chunks.iterator();
        while (chunks.hasNext()) {
            ChunkCoordinate chunk = chunks.next();
            long distance = Math.max(
                    Math.abs((long) chunk.x() - view.center.x()),
                    Math.abs((long) chunk.z() - view.center.z()));
            if (distance <= reach) {
                continue;
            }
            chunks.remove();
            view.releasedChunks.add(chunk);
            Iterator<Map.Entry<Integer, WithheldEntity>> entities =
                    view.entities.entrySet().iterator();
            while (entities.hasNext()) {
                Map.Entry<Integer, WithheldEntity> entry = entities.next();
                if (chunk.equals(entry.getValue().chunk)) {
                    view.releasedEntities.add(entry.getKey());
                    entities.remove();
                }
            }
        }
    }

    private static final class View {
        private boolean dead;
        private boolean tearingDown;
        private String world;
        private ChunkCoordinate center;
        private int radius = -1;
        private final Set<ChunkCoordinate> chunks = new HashSet<>();
        private final Map<Integer, WithheldEntity> entities = new HashMap<>();
        private final List<ChunkCoordinate> releasedChunks = new ArrayList<>();
        private final List<Integer> releasedEntities = new ArrayList<>();
    }

    private static final class WithheldEntity {
        private final ChunkCoordinate chunk;
        private long dueTick = NOT_SCHEDULED;

        private WithheldEntity(ChunkCoordinate chunk) {
            this.chunk = chunk;
        }
    }
}
