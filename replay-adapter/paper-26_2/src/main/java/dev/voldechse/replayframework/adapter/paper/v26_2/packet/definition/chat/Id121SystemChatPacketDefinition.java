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
 * <p>Messages the server broadcasts to everyone, such as death, join, quit and
 * advancement messages, are recorded once and replayed. Messages to one player,
 * such as command feedback or the action bar, are neither recorded nor replayed.
 * The text of a recorded message stays eligible for chat-policy filtering.</p>
 */
final class Id121SystemChatPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x79;
    static final String IDENTIFIER = "minecraft:system_chat";

    Id121SystemChatPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.configurable(PacketDescriptor.PlaybackScope.SAFE));
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

    /** Private messages are never recorded; see {@link PublicChat}. */
    @Override
    public boolean suppressesRecipientState(
            Object decodedPacket,
            dev.voldechse.replayframework.adapter.paper.packet.PacketCaptureState state,
            long serverTick) {
        return !(isPublic(decodedPacket));
    }

    /** Public chat is not a place: every recording of the world accepts it. */
    @Override
    public boolean worldWide() {
        return true;
    }

    /** Every recipient gets the same broadcast; one copy is enough. */
    @Override
    public boolean deduplicateRecipientCopies() {
        return true;
    }

    @Override
    protected dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult rewriteSafe(
            Object decodedPacket,
            Paper26_2ReplayPacketRewriter context) {
        return isPublic(decodedPacket)
                ? dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult.send(decodedPacket)
                : dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult.drop("system message was addressed to one player");
    }

    private static boolean isPublic(Object decodedPacket) {
        ClientboundSystemChatPacket packet = ClientboundSystemChatPacket.class.cast(decodedPacket);
        return PublicChat.isPublicSystemMessage(packet.content(), packet.overlay());
    }
}
