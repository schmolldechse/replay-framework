package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.scoreboard;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_display_objective}.
 *
 * <p>The packet selects or clears one of the receiving client's scoreboard
 * display slots: the player list, sidebar, below-name display, or a
 * team-colored sidebar. Sending recorded choices could replace the replay
 * observer's own scoreboard presentation, so playback drops the packet before
 * decoding it. The empty objective name is the protocol's clear-slot value.</p>
 */
final class Id098SetDisplayObjectivePacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x62;
    static final String IDENTIFIER = "minecraft:set_display_objective";

    Id098SetDisplayObjectivePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
