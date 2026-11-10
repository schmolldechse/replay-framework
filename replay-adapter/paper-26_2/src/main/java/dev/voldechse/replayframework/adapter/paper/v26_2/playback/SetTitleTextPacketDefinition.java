package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_title_text}. */
final class SetTitleTextPacketDefinition extends CatalogPacketDefinition {

    SetTitleTextPacketDefinition(PacketClassification classification) {
        super("minecraft:set_title_text", classification);
    }
}

