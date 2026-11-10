package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:chunks_biomes}. */
final class ChunksBiomesPacketDefinition extends CatalogPacketDefinition {

    ChunksBiomesPacketDefinition(PacketClassification classification) {
        super("minecraft:chunks_biomes", classification);
    }
}

