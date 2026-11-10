package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_health}. */
final class SetHealthPacketDefinition extends CatalogPacketDefinition {

    SetHealthPacketDefinition(PacketClassification classification) {
        super("minecraft:set_health", classification);
    }
}

