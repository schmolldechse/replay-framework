package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:stop_sound}. */
final class StopSoundPacketDefinition extends CatalogPacketDefinition {

    StopSoundPacketDefinition(PacketClassification classification) {
        super("minecraft:stop_sound", classification);
    }
}

