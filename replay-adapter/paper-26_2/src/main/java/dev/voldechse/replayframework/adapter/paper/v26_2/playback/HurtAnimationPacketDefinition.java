package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:hurt_animation}. */
final class HurtAnimationPacketDefinition extends CatalogPacketDefinition {

    HurtAnimationPacketDefinition(PacketClassification classification) {
        super("minecraft:hurt_animation", classification, Behavior.IDENTITY);
    }
}

