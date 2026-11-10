package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_cursor_item}. */
final class SetCursorItemPacketDefinition extends CatalogPacketDefinition {

    SetCursorItemPacketDefinition(PacketClassification classification) {
        super("minecraft:set_cursor_item", classification);
    }
}

