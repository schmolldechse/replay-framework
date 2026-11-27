package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Payload-free start marker for a receiving connection's chunk-batch timer.
 *
 * <p>Its meaning depends on the live client timer and a subsequent finish
 * marker. Replaying it cannot restore world state and could corrupt the real
 * connection's chunk-rate measurement.</p>
 */
final class Id012ChunkBatchStartPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x0C;
    static final String IDENTIFIER = "minecraft:chunk_batch_start";

    Id012ChunkBatchStartPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.VIEW_LOCAL;
    }
}
