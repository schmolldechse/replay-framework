package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:award_stats}.
 *
 * <p>The payload replaces statistics on the receiving client's own player and
 * refreshes its statistics screen. Replaying recorded values would therefore
 * mutate the neutral observer instead of reconstructing scene state. The
 * {@link PacketDefinition} base handles this scope before payload decode.</p>
 */
final class Id003AwardStatsPacketDefinition
        extends PacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x03;
    static final String IDENTIFIER = "minecraft:award_stats";

    Id003AwardStatsPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
