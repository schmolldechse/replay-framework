package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:swing_animation}. */
final class SwingAnimationPacketDefinition extends CatalogPacketDefinition {

    SwingAnimationPacketDefinition(PacketClassification classification) {
        super("minecraft:swing_animation", classification, Behavior.IDENTITY);
    }
}

