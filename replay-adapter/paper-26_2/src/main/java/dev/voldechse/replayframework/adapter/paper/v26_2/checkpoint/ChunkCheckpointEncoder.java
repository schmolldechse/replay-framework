package dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint;

import java.util.List;

/** Encodes detached chunk, light and block-entity blueprint groups. */
final class ChunkCheckpointEncoder {

    List<Paper26CheckpointEncoder.PacketBlueprint> encode(
            Paper26CheckpointEncoder.CheckpointSnapshot snapshot) {
        return snapshot.packets().stream()
                .filter(packet -> packet.family()
                        == Paper26CheckpointEncoder.CheckpointPacketFamily.CHUNK_LIGHT
                        || packet.family()
                        == Paper26CheckpointEncoder.CheckpointPacketFamily.BLOCK_ENTITY)
                .toList();
    }
}
