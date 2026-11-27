package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:chunk_batch_received}.
 *
 * Reports the client's chunk throughput. Connection state only.
 */
final class Id011ChunkBatchReceivedPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x0B;
    static final String IDENTIFIER = "minecraft:chunk_batch_received";

    Id011ChunkBatchReceivedPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
