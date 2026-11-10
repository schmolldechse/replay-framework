package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:update_tags}. */
final class UpdateTagsPacketDefinition extends CatalogPacketDefinition {

    UpdateTagsPacketDefinition(PacketClassification classification) {
        super("minecraft:update_tags", classification);
    }
}

