package dev.voldechse.replayframework.adapter.paper.v26_3.checkpoint;

import java.util.List;

/** Encodes detached chunk, light and block-entity blueprint groups. */
final class ChunkCheckpointEncoder {

    List<Paper26_3CheckpointEncoder.PacketBlueprint> encode(
            Paper26_3CheckpointEncoder.CheckpointSnapshot snapshot) {
        return snapshot.packets().stream()
                .filter(packet -> packet.family()
                        == Paper26_3CheckpointEncoder.CheckpointPacketFamily.CHUNK_LIGHT
                        || packet.family()
                        == Paper26_3CheckpointEncoder.CheckpointPacketFamily.BLOCK_ENTITY)
                .toList();
    }
}
