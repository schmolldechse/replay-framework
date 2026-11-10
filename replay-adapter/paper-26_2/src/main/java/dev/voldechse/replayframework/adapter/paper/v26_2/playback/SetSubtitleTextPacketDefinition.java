package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_subtitle_text}. */
final class SetSubtitleTextPacketDefinition extends CatalogPacketDefinition {

    SetSubtitleTextPacketDefinition(PacketClassification classification) {
        super("minecraft:set_subtitle_text", classification);
    }
}

