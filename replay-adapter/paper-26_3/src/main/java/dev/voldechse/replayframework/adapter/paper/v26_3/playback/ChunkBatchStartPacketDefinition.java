package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:chunk_batch_start}. */
final class ChunkBatchStartPacketDefinition extends CatalogPacketDefinition {

    ChunkBatchStartPacketDefinition(PacketClassification classification) {
        super("minecraft:chunk_batch_start", classification);
    }
}

