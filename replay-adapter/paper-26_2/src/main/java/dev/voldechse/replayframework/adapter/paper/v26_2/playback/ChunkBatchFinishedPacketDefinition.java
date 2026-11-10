package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:chunk_batch_finished}. */
final class ChunkBatchFinishedPacketDefinition extends CatalogPacketDefinition {

    ChunkBatchFinishedPacketDefinition(PacketClassification classification) {
        super("minecraft:chunk_batch_finished", classification);
    }
}

