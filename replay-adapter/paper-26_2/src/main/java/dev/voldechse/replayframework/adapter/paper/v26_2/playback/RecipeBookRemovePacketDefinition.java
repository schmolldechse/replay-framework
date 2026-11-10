package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:recipe_book_remove}. */
final class RecipeBookRemovePacketDefinition extends CatalogPacketDefinition {

    RecipeBookRemovePacketDefinition(PacketClassification classification) {
        super("minecraft:recipe_book_remove", classification);
    }
}

