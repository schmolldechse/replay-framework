package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.entity;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Captured local-vehicle correction policy for {@code minecraft:move_vehicle}.
 *
 * <p>The clientbound packet corrects the vehicle controlled by the recorded
 * player's connection. Playback must drop it before decode so the correction
 * cannot move a vehicle belonging to the observer.</p>
 */
final class Id058MoveVehiclePacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x3A;
    static final String IDENTIFIER = "minecraft:move_vehicle";

    Id058MoveVehiclePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
