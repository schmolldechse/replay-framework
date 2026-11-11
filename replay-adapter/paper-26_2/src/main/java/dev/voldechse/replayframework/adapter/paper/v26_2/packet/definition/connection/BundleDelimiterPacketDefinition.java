package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26ReplayPacketRewriter;

/**
 * Sequence-dependent, payload-free boundary of a clientbound packet bundle.
 *
 * <p>A delimiter does not identify whether it opens or closes a bundle. That
 * meaning depends on the viewer channel's preceding delimiter state, so an
 * isolated recorded frame cannot be decoded or replayed safely.</p>
 */
final class BundleDelimiterPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26ReplayPacketRewriter> {

    static final String IDENTIFIER = "minecraft:bundle_delimiter";

    BundleDelimiterPacketDefinition() {
        super(IDENTIFIER);
    }
}
