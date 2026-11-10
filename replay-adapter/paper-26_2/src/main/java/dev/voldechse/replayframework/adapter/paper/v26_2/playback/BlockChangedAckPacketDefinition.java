package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:block_changed_ack}. */
final class BlockChangedAckPacketDefinition extends CatalogPacketDefinition {

    BlockChangedAckPacketDefinition(PacketClassification classification) {
        super("minecraft:block_changed_ack", classification);
    }
}

