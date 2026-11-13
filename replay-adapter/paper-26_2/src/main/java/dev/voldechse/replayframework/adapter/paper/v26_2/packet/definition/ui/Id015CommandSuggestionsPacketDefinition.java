package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:command_suggestions}.
 *
 * <p>The packet correlates a transient completion response with the recorded
 * client's pending request and carries a replacement range, suggestion texts
 * and optional rich tooltips. Replaying even a structurally valid response can
 * target unrelated live input or expose completions that do not belong to the
 * observer. The viewer-local boundary therefore drops every recorded variant
 * before decoding, while the observer's genuine live responses remain owned by
 * the live connection.</p>
 */
final class Id015CommandSuggestionsPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x0F;
    static final String IDENTIFIER = "minecraft:command_suggestions";

    Id015CommandSuggestionsPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
