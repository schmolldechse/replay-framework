package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:level_chunk_with_light}. */
final class LevelChunkWithLightPacketDefinition extends CatalogPacketDefinition {

    LevelChunkWithLightPacketDefinition(PacketClassification classification) {
        super("minecraft:level_chunk_with_light", classification);
    }
}

