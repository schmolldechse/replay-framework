package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.chat;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:custom_chat_completions}.
 *
 * <p>The packet adds, removes or replaces the receiving client's persistent
 * set of custom chat suggestions. Player-name suggestions are managed
 * separately and cannot be removed through this packet. Applying the recorded
 * connection's set would contaminate the observer's own suggestions, so the
 * raw frame is captured but dropped before decode during playback. Genuine
 * live suggestion updates remain untouched.</p>
 */
final class Id023CustomChatCompletionsPacketDefinition
        extends PacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x17;
    static final String IDENTIFIER = "minecraft:custom_chat_completions";

    Id023CustomChatCompletionsPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
