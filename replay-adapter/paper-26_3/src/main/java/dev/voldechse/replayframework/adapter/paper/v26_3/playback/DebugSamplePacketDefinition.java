package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:debug_sample}. */
final class DebugSamplePacketDefinition extends CatalogPacketDefinition {

    DebugSamplePacketDefinition(PacketClassification classification) {
        super("minecraft:debug_sample", classification);
    }
}

