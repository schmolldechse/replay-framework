package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:open_sign_editor}. */
final class OpenSignEditorPacketDefinition extends CatalogPacketDefinition {

    OpenSignEditorPacketDefinition(PacketClassification classification) {
        super("minecraft:open_sign_editor", classification);
    }
}

