package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_display_objective}. */
final class SetDisplayObjectivePacketDefinition extends CatalogPacketDefinition {

    SetDisplayObjectivePacketDefinition(PacketClassification classification) {
        super("minecraft:set_display_objective", classification);
    }
}

