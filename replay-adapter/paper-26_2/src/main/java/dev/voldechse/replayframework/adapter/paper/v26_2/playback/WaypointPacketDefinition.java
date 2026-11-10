package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:waypoint}. */
final class WaypointPacketDefinition extends CatalogPacketDefinition {

    WaypointPacketDefinition(PacketClassification classification) {
        super("minecraft:waypoint", classification);
    }
}

