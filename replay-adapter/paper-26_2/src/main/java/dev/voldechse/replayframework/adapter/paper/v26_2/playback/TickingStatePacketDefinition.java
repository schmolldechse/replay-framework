package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:ticking_state}. */
final class TickingStatePacketDefinition extends CatalogPacketDefinition {

    TickingStatePacketDefinition(PacketClassification classification) {
        super("minecraft:ticking_state", classification);
    }
}

