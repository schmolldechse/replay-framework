package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.ui;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:seen_advancements}.
 *
 * Opens an advancement tab. Not part of the scene.
 */
final class Id050SeenAdvancementsPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x32;
    static final String IDENTIFIER = "minecraft:seen_advancements";

    Id050SeenAdvancementsPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
