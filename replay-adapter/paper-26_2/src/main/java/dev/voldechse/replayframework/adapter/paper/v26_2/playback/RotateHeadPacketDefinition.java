package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:rotate_head}. */
final class RotateHeadPacketDefinition extends CatalogPacketDefinition {

    RotateHeadPacketDefinition(PacketClassification classification) {
        super("minecraft:rotate_head", classification, Behavior.IDENTITY);
    }
}

