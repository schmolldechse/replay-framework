package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_chunk_cache_radius}. */
final class SetChunkCacheRadiusPacketDefinition extends CatalogPacketDefinition {

    SetChunkCacheRadiusPacketDefinition(PacketClassification classification) {
        super("minecraft:set_chunk_cache_radius", classification);
    }
}

