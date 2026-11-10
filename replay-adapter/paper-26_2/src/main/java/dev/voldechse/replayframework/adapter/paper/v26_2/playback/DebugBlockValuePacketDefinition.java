package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:debug/block_value}. */
final class DebugBlockValuePacketDefinition extends CatalogPacketDefinition {

    DebugBlockValuePacketDefinition(PacketClassification classification) {
        super("minecraft:debug/block_value", classification);
    }
}

