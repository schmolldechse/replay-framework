package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_titles_animation}.
 *
 * <p>The fade-in, stay and fade-out durations remain the receiving client's active title timing
 * configuration until changed or reset. Capture the recorded values, but drop them before decode
 * and forwarding so replay does not alter the observer's own title HUD.</p>
 */
final class Id115SetTitlesAnimationPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x73;
    static final String IDENTIFIER = "minecraft:set_titles_animation";

    Id115SetTitlesAnimationPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
