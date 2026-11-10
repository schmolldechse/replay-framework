package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_simulation_distance}. */
final class SetSimulationDistancePacketDefinition extends CatalogPacketDefinition {

    SetSimulationDistancePacketDefinition(PacketClassification classification) {
        super("minecraft:set_simulation_distance", classification);
    }
}

