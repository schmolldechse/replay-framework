package dev.voldechse.replayframework.adapter.paper.packet.clientbound;

/**
 * Semantic state families used to assemble deterministic replay checkpoints.
 * They are declared in assembly order: a checkpoint holds the dimension
 * first, then chunks, block entities, global state, and the entities with
 * their metadata, equipment, passengers and effects last.
 */
public enum PacketCheckpointFamily {
    DIMENSION_SPAWN,
    CHUNK_LIGHT,
    BLOCK_ENTITY,
    GLOBAL_STATE,
    ENTITY_SPAWN,
    ENTITY_METADATA,
    EQUIPMENT,
    PASSENGERS,
    RUNNING_EFFECT;

    /** The position of this family within a checkpoint. */
    public int order() {
        return ordinal();
    }
}
