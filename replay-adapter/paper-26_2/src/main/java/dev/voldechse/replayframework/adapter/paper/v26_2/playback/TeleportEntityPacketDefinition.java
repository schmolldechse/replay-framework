package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:teleport_entity}. */
final class TeleportEntityPacketDefinition extends CatalogPacketDefinition {

    TeleportEntityPacketDefinition(PacketClassification classification) {
        super("minecraft:teleport_entity", classification, Behavior.IDENTITY);
    }
}

