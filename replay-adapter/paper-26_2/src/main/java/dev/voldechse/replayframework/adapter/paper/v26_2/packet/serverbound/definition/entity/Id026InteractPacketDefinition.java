package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.entity;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:interact}.
 *
 * Uses or aims at an entity. Most results reach the recording as
 * clientbound packets. Known gap: igniting a creeper, giving an item to an
 * allay, dyeing a sheep, waxing or scraping a copper golem and milking play
 * their sound to everyone except the sender; they are not derived yet.
 */
final class Id026InteractPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x1A;
    static final String IDENTIFIER = "minecraft:interact";

    Id026InteractPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
