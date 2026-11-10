package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:ticking_step}. */
final class TickingStepPacketDefinition extends CatalogPacketDefinition {

    TickingStepPacketDefinition(PacketClassification classification) {
        super("minecraft:ticking_step", classification);
    }
}

