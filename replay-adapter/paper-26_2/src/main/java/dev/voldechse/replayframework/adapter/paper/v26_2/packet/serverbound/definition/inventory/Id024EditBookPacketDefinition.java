package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:edit_book}.
 *
 * Edits a book in the sender's inventory. Not part of the scene.
 */
final class Id024EditBookPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x18;
    static final String IDENTIFIER = "minecraft:edit_book";

    Id024EditBookPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
