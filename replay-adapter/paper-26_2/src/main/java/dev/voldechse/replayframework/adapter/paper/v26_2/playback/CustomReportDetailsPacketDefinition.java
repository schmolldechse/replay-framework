package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:custom_report_details}. */
final class CustomReportDetailsPacketDefinition extends CatalogPacketDefinition {

    CustomReportDetailsPacketDefinition(PacketClassification classification) {
        super("minecraft:custom_report_details", classification);
    }
}

