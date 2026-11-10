package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_entity_motion}. */
final class SetEntityMotionPacketDefinition extends CatalogPacketDefinition {

    SetEntityMotionPacketDefinition(PacketClassification classification) {
        super("minecraft:set_entity_motion", classification, Behavior.IDENTITY);
    }
}

