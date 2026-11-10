package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:sound}. */
final class SoundPacketDefinition extends CatalogPacketDefinition {

    SoundPacketDefinition(PacketClassification classification) {
        super("minecraft:sound", classification);
    }
}

