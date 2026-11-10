package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:level_event}. */
final class LevelEventPacketDefinition extends CatalogPacketDefinition {

    LevelEventPacketDefinition(PacketClassification classification) {
        super("minecraft:level_event", classification);
    }
}

