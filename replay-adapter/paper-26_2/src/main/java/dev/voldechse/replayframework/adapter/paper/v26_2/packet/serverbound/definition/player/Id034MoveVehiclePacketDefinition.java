package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;

/**
 * Serverbound contract for {@code minecraft:move_vehicle}.
 *
 * Moves the vehicle the sender rides. The vehicle reaches the recording
 * through the tracker; the rider is sampled.
 */
final class Id034MoveVehiclePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x22;
    static final String IDENTIFIER = "minecraft:move_vehicle";

    Id034MoveVehiclePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public ServerboundReaction react(Object decodedPacket) {
        super.react(decodedPacket);
        return ServerboundReaction.PLAYER_STATE;
    }
}
