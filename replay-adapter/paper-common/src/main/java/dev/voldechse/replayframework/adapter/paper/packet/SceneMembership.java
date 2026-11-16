package dev.voldechse.replayframework.adapter.paper.packet;

import java.util.Objects;

/**
 * What a {@link PacketDistribution#VIEW_MEMBERSHIP} packet adds to or removes
 * from its recipient's view.
 *
 * @param kind change the packet applies
 * @param chunkX chunk X for chunk changes
 * @param chunkZ chunk Z for chunk changes
 * @param entityIds affected protocol entity IDs for entity changes
 */
public record SceneMembership(Kind kind, int chunkX, int chunkZ, int[] entityIds) {

    /** The four view changes the protocol expresses. */
    public enum Kind { CHUNK_GAINED, CHUNK_LOST, ENTITY_GAINED, ENTITIES_LOST }

    public SceneMembership {
        Objects.requireNonNull(kind, "kind");
        entityIds = Objects.requireNonNull(entityIds, "entityIds").clone();
    }

    public static SceneMembership chunkGained(int chunkX, int chunkZ) {
        return new SceneMembership(Kind.CHUNK_GAINED, chunkX, chunkZ, new int[0]);
    }

    public static SceneMembership chunkLost(int chunkX, int chunkZ) {
        return new SceneMembership(Kind.CHUNK_LOST, chunkX, chunkZ, new int[0]);
    }

    public static SceneMembership entityGained(int entityId) {
        return new SceneMembership(Kind.ENTITY_GAINED, 0, 0, new int[] {entityId});
    }

    public static SceneMembership entitiesLost(int[] entityIds) {
        return new SceneMembership(Kind.ENTITIES_LOST, 0, 0, entityIds);
    }

    @Override
    public int[] entityIds() {
        return entityIds.clone();
    }
}
