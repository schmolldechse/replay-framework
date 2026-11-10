package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_time}. */
final class SetTimePacketDefinition extends CatalogPacketDefinition {

    SetTimePacketDefinition(PacketClassification classification) {
        super("minecraft:set_time", classification);
    }
}

