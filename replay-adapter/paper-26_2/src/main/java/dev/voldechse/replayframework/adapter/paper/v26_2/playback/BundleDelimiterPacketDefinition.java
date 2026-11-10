package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:bundle_delimiter}. */
final class BundleDelimiterPacketDefinition extends CatalogPacketDefinition {

    BundleDelimiterPacketDefinition(PacketClassification classification) {
        super("minecraft:bundle_delimiter", classification);
    }
}

