package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.chat;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:disguised_chat}.
 *
 * <p>The packet contains arbitrary chat content and a bound chat type: a
 * registry-backed decoration, sender name, and optional target name. These
 * values describe how a message from the recorded connection is presented in
 * that connection's chat history. Keep the raw packet for recording, but do
 * not apply it to the neutral observer's local chat.</p>
 */
final class Id033DisguisedChatPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x21;
    static final String IDENTIFIER = "minecraft:disguised_chat";

    Id033DisguisedChatPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
