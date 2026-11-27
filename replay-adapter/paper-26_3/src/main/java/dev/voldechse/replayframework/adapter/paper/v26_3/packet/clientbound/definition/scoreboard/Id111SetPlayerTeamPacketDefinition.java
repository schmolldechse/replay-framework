package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.scoreboard;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_player_team}.
 *
 * <p>The packet creates, changes or removes teams in the receiving client's
 * scoreboard and applies team presentation to its members. Those teams belong
 * to the recorded connection, so replay keeps the packet but drops it before
 * decoding or forwarding it to preserve the observer's scoreboard.</p>
 */
final class Id111SetPlayerTeamPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x6F;
    static final String IDENTIFIER = "minecraft:set_player_team";

    Id111SetPlayerTeamPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
