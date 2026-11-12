package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Payload-free start marker for a receiving connection's chunk-batch timer.
 *
 * <p>Its meaning depends on the live client timer and a subsequent finish
 * marker. Replaying it cannot restore world state and could corrupt the real
 * connection's chunk-rate measurement.</p>
 */
final class Id012ChunkBatchStartPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x0C;
    static final String IDENTIFIER = "minecraft:chunk_batch_start";

    Id012ChunkBatchStartPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
