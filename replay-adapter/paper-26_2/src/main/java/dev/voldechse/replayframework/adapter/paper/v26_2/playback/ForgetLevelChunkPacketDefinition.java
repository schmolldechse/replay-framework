package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:forget_level_chunk}. */
final class ForgetLevelChunkPacketDefinition extends CatalogPacketDefinition {

    ForgetLevelChunkPacketDefinition(PacketClassification classification) {
        super("minecraft:forget_level_chunk", classification);
    }
}

