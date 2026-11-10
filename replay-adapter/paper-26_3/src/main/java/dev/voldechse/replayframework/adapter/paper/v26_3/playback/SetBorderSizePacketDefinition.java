package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_border_size}. */
final class SetBorderSizePacketDefinition extends CatalogPacketDefinition {

    SetBorderSizePacketDefinition(PacketClassification classification) {
        super("minecraft:set_border_size", classification);
    }
}

