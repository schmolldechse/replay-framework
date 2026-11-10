package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_equipment}. */
final class SetEquipmentPacketDefinition extends CatalogPacketDefinition {

    SetEquipmentPacketDefinition(PacketClassification classification) {
        super("minecraft:set_equipment", classification, Behavior.IDENTITY);
    }
}

