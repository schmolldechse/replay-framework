package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:server_data}. */
final class ServerDataPacketDefinition extends CatalogPacketDefinition {

    ServerDataPacketDefinition(PacketClassification classification) {
        super("minecraft:server_data", classification);
    }
}

