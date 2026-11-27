package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:set_beacon}.
 *
 * Selects beacon effects. The beacon sound is sent to every player.
 */
final class Id053SetBeaconPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x35;
    static final String IDENTIFIER = "minecraft:set_beacon";

    Id053SetBeaconPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
