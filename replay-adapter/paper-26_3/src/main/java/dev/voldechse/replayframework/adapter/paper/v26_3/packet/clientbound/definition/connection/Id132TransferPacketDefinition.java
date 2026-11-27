package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Captures a request for the client to reconnect to a different server.
 *
 * <p>Replaying the host and port would redirect the observing client away from the replay session.</p>
 */
final class Id132TransferPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x84;
    static final String IDENTIFIER = "minecraft:transfer";

    Id132TransferPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
