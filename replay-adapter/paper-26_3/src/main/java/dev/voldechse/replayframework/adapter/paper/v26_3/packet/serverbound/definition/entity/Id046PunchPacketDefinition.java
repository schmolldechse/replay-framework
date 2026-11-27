package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.entity;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:punch}.
 *
 * A left click that hits nothing; it replaces {@code swing} of 26.2 and has
 * no fields. The server runs Paper's interaction logic; the arm swing reaches
 * the actor's trackers as {@code swing_animation}, so nothing is derived.
 */
final class Id046PunchPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x2E;
    static final String IDENTIFIER = "minecraft:punch";

    Id046PunchPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
