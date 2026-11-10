package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:tab_list}. */
final class TabListPacketDefinition extends CatalogPacketDefinition {

    TabListPacketDefinition(PacketClassification classification) {
        super("minecraft:tab_list", classification);
    }
}

