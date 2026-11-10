package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:take_item_entity}. */
final class TakeItemEntityPacketDefinition extends CatalogPacketDefinition {

    TakeItemEntityPacketDefinition(PacketClassification classification) {
        super("minecraft:take_item_entity", classification, Behavior.IDENTITY);
    }
}

