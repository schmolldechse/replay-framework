package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:debug/event}. */
final class DebugEventPacketDefinition extends CatalogPacketDefinition {

    DebugEventPacketDefinition(PacketClassification classification) {
        super("minecraft:debug/event", classification);
    }
}

