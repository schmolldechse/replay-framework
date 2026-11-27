package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.scoreboard;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:reset_score}.
 *
 * <p>The packet removes one score when an objective is present, or every score
 * belonging to its owner when the objective is absent. The owner is arbitrary
 * scoreboard text, not an entity ID. Replaying either variant could erase the
 * observer's scoreboard, so the packet stays in recordings but is dropped
 * before its payload is decoded for playback.</p>
 */
final class Id080ResetScorePacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x50;
    static final String IDENTIFIER = "minecraft:reset_score";

    Id080ResetScorePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
