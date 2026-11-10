package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:merchant_offers}. */
final class MerchantOffersPacketDefinition extends CatalogPacketDefinition {

    MerchantOffersPacketDefinition(PacketClassification classification) {
        super("minecraft:merchant_offers", classification);
    }
}

