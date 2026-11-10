package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:section_blocks_update}. */
final class SectionBlocksUpdatePacketDefinition extends CatalogPacketDefinition {

    SectionBlocksUpdatePacketDefinition(PacketClassification classification) {
        super("minecraft:section_blocks_update", classification);
    }
}

