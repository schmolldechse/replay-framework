package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Captured report metadata for {@code minecraft:custom_report_details}.
 *
 * <p>The client retains these key/value details for later crash and disconnect reports. They
 * belong to the server connection that supplied them, so playback must leave the observer's
 * connection-local report context unchanged.</p>
 */
final class Id136CustomReportDetailsPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x88;
    static final String IDENTIFIER = "minecraft:custom_report_details";

    Id136CustomReportDetailsPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
