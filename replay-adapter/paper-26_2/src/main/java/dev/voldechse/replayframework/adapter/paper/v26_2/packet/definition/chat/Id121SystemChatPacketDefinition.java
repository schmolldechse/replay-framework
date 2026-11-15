package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.chat;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.paper.packet.PacketCaptureState;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

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

    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            java.util.UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        CaptureContext base = super.captureContext(
                decodedPacket, descriptor, connectionId, state, serverTick);
        String text = ClientboundSystemChatPacket.class.cast(decodedPacket)
                .content().getString();
        return dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2CaptureContexts
                .withChat(base, text);
    }
}
