package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:transfer}. */
final class TransferPacketDefinition extends CatalogPacketDefinition {

    TransferPacketDefinition(PacketClassification classification) {
        super("minecraft:transfer", classification);
    }
}

