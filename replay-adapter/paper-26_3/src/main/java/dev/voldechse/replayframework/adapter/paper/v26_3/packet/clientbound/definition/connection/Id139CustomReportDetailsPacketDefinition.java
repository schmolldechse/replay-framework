package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Captured report metadata for {@code minecraft:custom_report_details}.
 *
 * <p>The client retains these key/value details for later crash and disconnect reports. They
 * belong to the server connection that supplied them, so playback must leave the observer's
 * connection-local report context unchanged.</p>
 */
final class Id139CustomReportDetailsPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x8B;
    static final String IDENTIFIER = "minecraft:custom_report_details";

    Id139CustomReportDetailsPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
