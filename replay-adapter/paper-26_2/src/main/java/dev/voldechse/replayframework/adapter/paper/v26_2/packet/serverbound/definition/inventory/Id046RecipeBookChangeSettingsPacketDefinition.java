package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:recipe_book_change_settings}.
 *
 * Changes recipe book settings. Not part of the scene.
 */
final class Id046RecipeBookChangeSettingsPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x2E;
    static final String IDENTIFIER = "minecraft:recipe_book_change_settings";

    Id046RecipeBookChangeSettingsPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
