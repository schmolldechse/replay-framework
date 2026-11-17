package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:place_recipe}.
 *
 * Fills a crafting grid from the recipe book. Not part of the scene.
 */
final class Id039PlaceRecipePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x27;
    static final String IDENTIFIER = "minecraft:place_recipe";

    Id039PlaceRecipePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
