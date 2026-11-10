package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:store_cookie}. */
final class StoreCookiePacketDefinition extends CatalogPacketDefinition {

    StoreCookiePacketDefinition(PacketClassification classification) {
        super("minecraft:store_cookie", classification);
    }
}

