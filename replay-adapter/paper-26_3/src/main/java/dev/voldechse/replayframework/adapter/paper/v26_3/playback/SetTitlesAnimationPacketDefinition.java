package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_titles_animation}. */
final class SetTitlesAnimationPacketDefinition extends CatalogPacketDefinition {

    SetTitlesAnimationPacketDefinition(PacketClassification classification) {
        super("minecraft:set_titles_animation", classification);
    }
}

