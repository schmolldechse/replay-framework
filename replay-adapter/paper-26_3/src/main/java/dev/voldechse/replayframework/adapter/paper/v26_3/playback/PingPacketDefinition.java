package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:ping}. */
final class PingPacketDefinition extends CatalogPacketDefinition {

    PingPacketDefinition(PacketClassification classification) {
        super("minecraft:ping", classification);
    }
}

