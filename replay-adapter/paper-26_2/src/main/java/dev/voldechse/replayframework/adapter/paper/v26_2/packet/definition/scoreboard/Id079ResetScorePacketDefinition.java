package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.scoreboard;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:reset_score}.
 *
 * <p>The packet removes one score when an objective is present, or every score
 * belonging to its owner when the objective is absent. The owner is arbitrary
 * scoreboard text, not an entity ID. Replaying either variant could erase the
 * observer's scoreboard, so the packet stays in recordings but is dropped
 * before its payload is decoded for playback.</p>
 */
final class Id079ResetScorePacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x4F;
    static final String IDENTIFIER = "minecraft:reset_score";

    Id079ResetScorePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
