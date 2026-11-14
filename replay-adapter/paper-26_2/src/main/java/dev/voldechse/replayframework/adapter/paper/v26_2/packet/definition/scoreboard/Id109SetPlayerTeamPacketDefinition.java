package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.scoreboard;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_player_team}.
 *
 * <p>The packet creates, changes or removes teams in the receiving client's
 * scoreboard and applies team presentation to its members. Those teams belong
 * to the recorded connection, so replay keeps the packet but drops it before
 * decoding or forwarding it to preserve the observer's scoreboard.</p>
 */
final class Id109SetPlayerTeamPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x6D;
    static final String IDENTIFIER = "minecraft:set_player_team";

    Id109SetPlayerTeamPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
