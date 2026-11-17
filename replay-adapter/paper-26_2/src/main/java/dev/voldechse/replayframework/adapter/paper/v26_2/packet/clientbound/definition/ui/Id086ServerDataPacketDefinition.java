package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:server_data}.
 *
 * <p>The packet carries connection-specific server presentation data: the
 * server's MOTD and optional icon. Applying the recorded connection's values
 * to the observer would replace their own server details, so playback keeps
 * this state local.</p>
 */
final class Id086ServerDataPacketDefinition
        extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x56;
    static final String IDENTIFIER = "minecraft:server_data";

    Id086ServerDataPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
