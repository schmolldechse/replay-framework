package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_experience}. */
final class SetExperiencePacketDefinition extends CatalogPacketDefinition {

    SetExperiencePacketDefinition(PacketClassification classification) {
        super("minecraft:set_experience", classification);
    }
}

