package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:reset_score}. */
final class ResetScorePacketDefinition extends CatalogPacketDefinition {

    ResetScorePacketDefinition(PacketClassification classification) {
        super("minecraft:reset_score", classification);
    }
}

