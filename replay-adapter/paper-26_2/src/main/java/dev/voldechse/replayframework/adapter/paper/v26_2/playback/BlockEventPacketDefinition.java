package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:block_event}. */
final class BlockEventPacketDefinition extends CatalogPacketDefinition {

    BlockEventPacketDefinition(PacketClassification classification) {
        super("minecraft:block_event", classification);
    }
}

