package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:recipe_book_add}. */
final class RecipeBookAddPacketDefinition extends CatalogPacketDefinition {

    RecipeBookAddPacketDefinition(PacketClassification classification) {
        super("minecraft:recipe_book_add", classification);
    }
}

