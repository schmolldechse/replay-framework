package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Captured local-vehicle correction policy for {@code minecraft:move_vehicle}.
 *
 * <p>The clientbound packet corrects the vehicle controlled by the recorded
 * player's connection. Playback must drop it before decode so the correction
 * cannot move a vehicle belonging to the observer.</p>
 */
final class Id057MoveVehiclePacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x39;
    static final String IDENTIFIER = "minecraft:move_vehicle";

    Id057MoveVehiclePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
