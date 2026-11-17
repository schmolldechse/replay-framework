package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Captures a request for the client to reconnect to a different server.
 *
 * <p>Replaying the host and port would redirect the observing client away from the replay session.</p>
 */
final class Id129TransferPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x81;
    static final String IDENTIFIER = "minecraft:transfer";

    Id129TransferPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
