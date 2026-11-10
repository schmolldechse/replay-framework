package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_chunk_cache_center}. */
final class SetChunkCacheCenterPacketDefinition extends CatalogPacketDefinition {

    SetChunkCacheCenterPacketDefinition(PacketClassification classification) {
        super("minecraft:set_chunk_cache_center", classification);
    }
}

