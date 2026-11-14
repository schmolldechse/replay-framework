package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.chat;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Capture-policy contract for {@code minecraft:system_chat}.
 *
 * <p>The text is eligible for chat-policy filtering, but the packet's chat-history/action-bar
 * mutation belongs to the recorded connection and is dropped before playback decoding.</p>
 */
final class Id121SystemChatPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x79;
    static final String IDENTIFIER = "minecraft:system_chat";

    Id121SystemChatPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.configurable(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
