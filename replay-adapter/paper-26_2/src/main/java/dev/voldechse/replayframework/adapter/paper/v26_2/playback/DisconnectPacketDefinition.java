package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:disconnect}. */
final class DisconnectPacketDefinition extends CatalogPacketDefinition {

    DisconnectPacketDefinition(PacketClassification classification) {
        super("minecraft:disconnect", classification);
    }
}

