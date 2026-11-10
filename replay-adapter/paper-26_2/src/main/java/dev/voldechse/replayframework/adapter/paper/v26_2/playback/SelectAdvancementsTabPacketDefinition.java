package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:select_advancements_tab}. */
final class SelectAdvancementsTabPacketDefinition extends CatalogPacketDefinition {

    SelectAdvancementsTabPacketDefinition(PacketClassification classification) {
        super("minecraft:select_advancements_tab", classification);
    }
}

