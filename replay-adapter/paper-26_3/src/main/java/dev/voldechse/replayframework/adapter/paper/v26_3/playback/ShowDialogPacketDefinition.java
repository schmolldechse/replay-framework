package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:show_dialog}. */
final class ShowDialogPacketDefinition extends CatalogPacketDefinition {

    ShowDialogPacketDefinition(PacketClassification classification) {
        super("minecraft:show_dialog", classification);
    }
}

