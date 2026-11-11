package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Sequence-dependent, payload-free boundary of a clientbound packet bundle.
 *
 * <p>A delimiter does not identify whether it opens or closes a bundle. That
 * meaning depends on the viewer channel's preceding delimiter state, so an
 * isolated recorded frame cannot be decoded or replayed safely.</p>
 */
final class Id000BundleDelimiterPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x00;
    static final String IDENTIFIER = "minecraft:bundle_delimiter";

    Id000BundleDelimiterPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
