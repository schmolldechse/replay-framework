package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Completes the receiving connection's current chunk-throughput measurement.
 *
 * <p>The batch size contributes to a client-generated rate acknowledgement for
 * the live server. Replaying a recorded marker would therefore mutate the
 * current connection's chunk flow control rather than restore world state.</p>
 */
final class Id011ChunkBatchFinishedPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x0B;
    static final String IDENTIFIER = "minecraft:chunk_batch_finished";

    Id011ChunkBatchFinishedPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
