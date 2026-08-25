package dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint;

import java.util.List;

/** Encodes dimension, spawn and global durable client-state groups. */
final class GlobalStateCheckpointEncoder {

    List<Paper26CheckpointEncoder.PacketBlueprint> encode(
            Paper26CheckpointEncoder.CheckpointSnapshot snapshot) {
        return snapshot.packets().stream()
                .filter(packet -> switch (packet.family()) {
                    case DIMENSION_SPAWN, GLOBAL_STATE -> true;
                    default -> false;
                })
                .toList();
    }
}
