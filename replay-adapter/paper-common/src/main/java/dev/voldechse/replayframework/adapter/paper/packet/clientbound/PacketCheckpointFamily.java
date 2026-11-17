package dev.voldechse.replayframework.adapter.paper.packet.clientbound;

/** Semantic state families used to assemble deterministic replay checkpoints. */
public enum PacketCheckpointFamily {
    DIMENSION_SPAWN,
    CHUNK_LIGHT,
    BLOCK_ENTITY,
    GLOBAL_STATE,
    ENTITY_SPAWN,
    ENTITY_METADATA,
    EQUIPMENT,
    PASSENGERS,
    RUNNING_EFFECT
}
