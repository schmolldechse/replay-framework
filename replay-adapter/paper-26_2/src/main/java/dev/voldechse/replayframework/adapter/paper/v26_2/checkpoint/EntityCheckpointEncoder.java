package dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint;

import java.util.List;

/** Encodes detached entity lifecycle, metadata and relationship groups. */
final class EntityCheckpointEncoder {

    List<Paper26CheckpointEncoder.PacketBlueprint> encode(
            Paper26CheckpointEncoder.CheckpointSnapshot snapshot) {
        return snapshot.packets().stream()
                .filter(packet -> switch (packet.family()) {
                    case ENTITY_SPAWN, ENTITY_METADATA, EQUIPMENT, PASSENGERS, RUNNING_EFFECT -> true;
                    default -> false;
                })
                .toList();
    }
}
