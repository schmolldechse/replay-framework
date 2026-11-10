package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:cookie_request}. */
final class CookieRequestPacketDefinition extends CatalogPacketDefinition {

    CookieRequestPacketDefinition(PacketClassification classification) {
        super("minecraft:cookie_request", classification);
    }
}

