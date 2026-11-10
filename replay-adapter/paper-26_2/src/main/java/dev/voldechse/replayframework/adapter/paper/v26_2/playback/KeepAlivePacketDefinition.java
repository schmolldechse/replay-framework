package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:keep_alive}. */
final class KeepAlivePacketDefinition extends CatalogPacketDefinition {

    KeepAlivePacketDefinition(PacketClassification classification) {
        super("minecraft:keep_alive", classification);
    }
}

