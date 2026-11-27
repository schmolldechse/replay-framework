package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Playback policy for the transient action-bar component sent by
 * {@code minecraft:set_action_bar_text}.
 *
 * <p>The message belongs to the recorded player's HUD and has no protocol
 * duration or clear operation. Capture it as a replay event, but preserve the
 * observer's own action bar during playback.</p>
 */
final class Id089SetActionBarTextPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x59;
    static final String IDENTIFIER = "minecraft:set_action_bar_text";

    Id089SetActionBarTextPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
