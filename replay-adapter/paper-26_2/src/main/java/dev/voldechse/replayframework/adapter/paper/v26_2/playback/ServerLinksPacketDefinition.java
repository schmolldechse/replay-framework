package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:server_links}. */
final class ServerLinksPacketDefinition extends CatalogPacketDefinition {

    ServerLinksPacketDefinition(PacketClassification classification) {
        super("minecraft:server_links", classification);
    }
}

