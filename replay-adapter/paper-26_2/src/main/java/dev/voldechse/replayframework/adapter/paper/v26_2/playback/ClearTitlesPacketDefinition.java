package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:clear_titles}. */
final class ClearTitlesPacketDefinition extends CatalogPacketDefinition {

    ClearTitlesPacketDefinition(PacketClassification classification) {
        super("minecraft:clear_titles", classification);
    }
}

