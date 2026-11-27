package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:recipe_book_seen_recipe}.
 *
 * Marks a recipe as seen. Not part of the scene.
 */
final class Id047RecipeBookSeenRecipePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x2F;
    static final String IDENTIFIER = "minecraft:recipe_book_seen_recipe";

    Id047RecipeBookSeenRecipePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
