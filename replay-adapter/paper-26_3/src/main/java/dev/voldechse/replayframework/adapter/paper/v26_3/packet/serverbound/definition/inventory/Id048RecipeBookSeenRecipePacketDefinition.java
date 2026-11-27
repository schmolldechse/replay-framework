package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:recipe_book_seen_recipe}.
 *
 * Marks a recipe as seen. Not part of the scene.
 */
final class Id048RecipeBookSeenRecipePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x30;
    static final String IDENTIFIER = "minecraft:recipe_book_seen_recipe";

    Id048RecipeBookSeenRecipePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
