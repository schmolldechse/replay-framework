package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Playback policy for the transient action-bar component sent by
 * {@code minecraft:set_action_bar_text}.
 *
 * <p>The message belongs to the recorded player's HUD and has no protocol
 * duration or clear operation. Capture it as a replay event, but preserve the
 * observer's own action bar during playback.</p>
 */
final class Id087SetActionBarTextPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x57;
    static final String IDENTIFIER = "minecraft:set_action_bar_text";

    Id087SetActionBarTextPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
