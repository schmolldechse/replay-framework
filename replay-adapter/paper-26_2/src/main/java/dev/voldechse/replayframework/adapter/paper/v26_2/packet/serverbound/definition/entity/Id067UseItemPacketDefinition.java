package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.entity;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:use_item}.
 *
 * Uses the held item in the air. Whatever it changes about the sender's own
 * entity reaches the recording through the entity tracker of the eye that
 * follows the sender. Known gap: bucket and bottle filling, goat horns,
 * lance sounds and eating or drinking are played to everyone except the
 * sender; they are not derived yet.
 */
final class Id067UseItemPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x43;
    static final String IDENTIFIER = "minecraft:use_item";

    Id067UseItemPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
