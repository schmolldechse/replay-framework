package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:debug/chunk_value}. */
final class DebugChunkValuePacketDefinition extends CatalogPacketDefinition {

    DebugChunkValuePacketDefinition(PacketClassification classification) {
        super("minecraft:debug/chunk_value", classification);
    }
}

