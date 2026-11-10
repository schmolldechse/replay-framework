package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_objective}. */
final class SetObjectivePacketDefinition extends CatalogPacketDefinition {

    SetObjectivePacketDefinition(PacketClassification classification) {
        super("minecraft:set_objective", classification);
    }
}

