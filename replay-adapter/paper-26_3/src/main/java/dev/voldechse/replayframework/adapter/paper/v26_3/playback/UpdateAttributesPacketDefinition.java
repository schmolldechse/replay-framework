package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:update_attributes}. */
final class UpdateAttributesPacketDefinition extends CatalogPacketDefinition {

    UpdateAttributesPacketDefinition(PacketClassification classification) {
        super("minecraft:update_attributes", classification, Behavior.IDENTITY);
    }
}

