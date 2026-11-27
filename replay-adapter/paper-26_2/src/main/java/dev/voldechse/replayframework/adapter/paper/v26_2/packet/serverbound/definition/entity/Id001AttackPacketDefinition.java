package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.entity;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:attack}.
 *
 * Attacks an entity. Hit, critical and sweep sounds, the damage event and
 * the victim's reaction reach every recipient, including the attacker.
 * Known gap: a piercing weapon (spear) plays its sound to everyone except
 * the attacker; it is not derived yet.
 */
final class Id001AttackPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x01;
    static final String IDENTIFIER = "minecraft:attack";

    Id001AttackPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
