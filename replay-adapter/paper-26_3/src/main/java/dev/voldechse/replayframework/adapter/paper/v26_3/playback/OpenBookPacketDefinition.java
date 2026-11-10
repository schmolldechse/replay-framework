package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:open_book}. */
final class OpenBookPacketDefinition extends CatalogPacketDefinition {

    OpenBookPacketDefinition(PacketClassification classification) {
        super("minecraft:open_book", classification);
    }
}

