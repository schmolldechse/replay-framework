package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.chat;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:delete_chat}.
 *
 * <p>Paper 26.2 identifies the signed message to remove using a packed
 * signature: either a connection-local cache index or the full 256-byte
 * signature. The packet only removes signed player-chat entries, but applies
 * to the receiving client's chat history. Replaying it could resolve an
 * index to a different message in the observer's signature cache; even the
 * full-signature form would mutate the observer's own chat. Capture the raw
 * packet, but drop it before playback decoding. Live observer chat remains
 * unaffected.</p>
 */
final class Id031DeleteChatPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x1F;
    static final String IDENTIFIER = "minecraft:delete_chat";

    Id031DeleteChatPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
