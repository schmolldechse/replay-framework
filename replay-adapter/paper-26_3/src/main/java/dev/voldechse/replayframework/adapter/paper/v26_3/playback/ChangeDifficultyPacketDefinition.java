package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:change_difficulty}. */
final class ChangeDifficultyPacketDefinition extends CatalogPacketDefinition {

    ChangeDifficultyPacketDefinition(PacketClassification classification) {
        super("minecraft:change_difficulty", classification);
    }
}

