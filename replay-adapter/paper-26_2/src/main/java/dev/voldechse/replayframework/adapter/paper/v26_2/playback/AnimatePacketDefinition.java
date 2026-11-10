package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:animate}. */
final class AnimatePacketDefinition extends CatalogPacketDefinition {

    AnimatePacketDefinition(PacketClassification classification) {
        super("minecraft:animate", classification, Behavior.IDENTITY);
    }
}

