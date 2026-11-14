package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_subtitle_text}.
 *
 * <p>The component is the receiving client's pending subtitle for a title sequence. Retain it
 * in the recording, but drop it before decode and forwarding so playback cannot replace the
 * observer's own title state.</p>
 */
final class Id112SetSubtitleTextPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x70;
    static final String IDENTIFIER = "minecraft:set_subtitle_text";

    Id112SetSubtitleTextPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
