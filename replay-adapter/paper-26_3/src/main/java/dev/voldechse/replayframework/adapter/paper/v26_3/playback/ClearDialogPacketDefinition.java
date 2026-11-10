package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:clear_dialog}. */
final class ClearDialogPacketDefinition extends CatalogPacketDefinition {

    ClearDialogPacketDefinition(PacketClassification classification) {
        super("minecraft:clear_dialog", classification);
    }
}

