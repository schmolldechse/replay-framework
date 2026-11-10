package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:update_advancements}. */
final class UpdateAdvancementsPacketDefinition extends CatalogPacketDefinition {

    UpdateAdvancementsPacketDefinition(PacketClassification classification) {
        super("minecraft:update_advancements", classification);
    }
}

