package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:award_stats}. */
final class AwardStatsPacketDefinition extends CatalogPacketDefinition {

    AwardStatsPacketDefinition(PacketClassification classification) {
        super("minecraft:award_stats", classification);
    }
}

