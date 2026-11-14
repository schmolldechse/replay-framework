package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_title_text}.
 *
 * <p>The text becomes the receiving client's current title and is presented according to the
 * separately delivered title animation timings. It belongs to the recorded player's HUD, so
 * replay captures the packet but drops it before decoding or forwarding to preserve the
 * observer's own title state.</p>
 */
final class Id114SetTitleTextPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x72;
    static final String IDENTIFIER = "minecraft:set_title_text";

    Id114SetTitleTextPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
