package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:recipe_book_settings}. */
final class RecipeBookSettingsPacketDefinition extends CatalogPacketDefinition {

    RecipeBookSettingsPacketDefinition(PacketClassification classification) {
        super("minecraft:recipe_book_settings", classification);
    }
}

