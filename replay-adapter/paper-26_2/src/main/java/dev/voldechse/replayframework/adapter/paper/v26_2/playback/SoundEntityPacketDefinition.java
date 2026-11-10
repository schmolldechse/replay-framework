package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:sound_entity}. */
final class SoundEntityPacketDefinition extends CatalogPacketDefinition {

    SoundEntityPacketDefinition(PacketClassification classification) {
        super("minecraft:sound_entity", classification, Behavior.IDENTITY);
    }
}

