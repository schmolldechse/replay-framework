package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:rename_item}.
 *
 * Sets the anvil name. The anvil sound on taking the result is sent to
 * every player.
 */
final class Id048RenameItemPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x30;
    static final String IDENTIFIER = "minecraft:rename_item";

    Id048RenameItemPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
