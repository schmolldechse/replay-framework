package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_action_bar_text}. */
final class SetActionBarTextPacketDefinition extends CatalogPacketDefinition {

    SetActionBarTextPacketDefinition(PacketClassification classification) {
        super("minecraft:set_action_bar_text", classification);
    }
}

