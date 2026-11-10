package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:map_item_data}. */
final class MapItemDataPacketDefinition extends CatalogPacketDefinition {

    MapItemDataPacketDefinition(PacketClassification classification) {
        super("minecraft:map_item_data", classification);
    }
}

