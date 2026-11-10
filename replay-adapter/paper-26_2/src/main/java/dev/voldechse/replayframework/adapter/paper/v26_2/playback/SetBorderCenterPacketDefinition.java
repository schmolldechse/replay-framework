package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_border_center}. */
final class SetBorderCenterPacketDefinition extends CatalogPacketDefinition {

    SetBorderCenterPacketDefinition(PacketClassification classification) {
        super("minecraft:set_border_center", classification);
    }
}

