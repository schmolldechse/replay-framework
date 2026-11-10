package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:tag_query}. */
final class TagQueryPacketDefinition extends CatalogPacketDefinition {

    TagQueryPacketDefinition(PacketClassification classification) {
        super("minecraft:tag_query", classification);
    }
}

