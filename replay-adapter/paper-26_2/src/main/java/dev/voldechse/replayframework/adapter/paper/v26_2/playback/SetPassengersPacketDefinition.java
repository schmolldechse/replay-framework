package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_passengers}. */
final class SetPassengersPacketDefinition extends CatalogPacketDefinition {

    SetPassengersPacketDefinition(PacketClassification classification) {
        super("minecraft:set_passengers", classification, Behavior.IDENTITY);
    }
}

