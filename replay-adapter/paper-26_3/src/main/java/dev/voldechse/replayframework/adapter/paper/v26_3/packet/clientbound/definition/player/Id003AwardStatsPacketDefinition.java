package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:award_stats}.
 *
 * <p>The payload replaces statistics on the receiving client's own player and
 * refreshes its statistics screen. Replaying recorded values would therefore
 * mutate the neutral observer instead of reconstructing scene state. The
 * {@link ClientboundPacketDefinition} base handles this scope before payload decode.</p>
 */
final class Id003AwardStatsPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

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
