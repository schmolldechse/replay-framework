package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:player_abilities}.
 *
 * Starts or stops creative flight. The resulting movement arrives with
 * movement packets.
 */
final class Id040PlayerAbilitiesPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x28;
    static final String IDENTIFIER = "minecraft:player_abilities";

    Id040PlayerAbilitiesPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
