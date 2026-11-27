package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_title_text}.
 *
 * <p>The text becomes the receiving client's current title and is presented according to the
 * separately delivered title animation timings. It belongs to the recorded player's HUD, so
 * replay captures the packet but drops it before decoding or forwarding to preserve the
 * observer's own title state.</p>
 */
final class Id116SetTitleTextPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x74;
    static final String IDENTIFIER = "minecraft:set_title_text";

    Id116SetTitleTextPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
