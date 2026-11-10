package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:update_recipes}. */
final class UpdateRecipesPacketDefinition extends CatalogPacketDefinition {

    UpdateRecipesPacketDefinition(PacketClassification classification) {
        super("minecraft:update_recipes", classification);
    }
}

