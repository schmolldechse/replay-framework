package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:block_update}. */
final class BlockUpdatePacketDefinition extends CatalogPacketDefinition {

    BlockUpdatePacketDefinition(PacketClassification classification) {
        super("minecraft:block_update", classification);
    }
}

