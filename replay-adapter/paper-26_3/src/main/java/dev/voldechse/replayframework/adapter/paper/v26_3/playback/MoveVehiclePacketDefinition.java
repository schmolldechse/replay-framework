package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:move_vehicle}. */
final class MoveVehiclePacketDefinition extends CatalogPacketDefinition {

    MoveVehiclePacketDefinition(PacketClassification classification) {
        super("minecraft:move_vehicle", classification);
    }
}

