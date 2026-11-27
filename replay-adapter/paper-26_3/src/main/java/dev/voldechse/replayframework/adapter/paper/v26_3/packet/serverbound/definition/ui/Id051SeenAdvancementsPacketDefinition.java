package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.ui;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:seen_advancements}.
 *
 * Opens an advancement tab. Not part of the scene.
 */
final class Id051SeenAdvancementsPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x33;
    static final String IDENTIFIER = "minecraft:seen_advancements";

    Id051SeenAdvancementsPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
