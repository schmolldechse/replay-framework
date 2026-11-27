package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.chat;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientCopies;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
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
        extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

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
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.CaptureContexts
                .withChat(base, text);
    }

    /** Private messages are never recorded; see {@link PublicChat}. */
    @Override
    public boolean suppressesRecipientState(
            Object decodedPacket,
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState state,
            long serverTick) {
        return !(isPublic(decodedPacket));
    }

    /** Public chat is not a place: every recording of the world accepts it. */
    @Override
    public boolean worldWide() {
        return true;
    }

    @Override
    protected dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult rewriteSafe(
            Object decodedPacket,
            Paper26_2ReplayPacketRewriter context) {
        return isPublic(decodedPacket)
                ? dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult.send(decodedPacket)
                : dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult.drop("system message was addressed to one player");
    }

    private static boolean isPublic(Object decodedPacket) {
        ClientboundSystemChatPacket packet = ClientboundSystemChatPacket.class.cast(decodedPacket);
        return PublicChat.isPublicSystemMessage(packet.content(), packet.overlay());
    }

    /** A broadcast message has equal bytes for every recipient. */
    @Override
    public RecipientCopies recipientCopies() {
        return RecipientCopies.identicalPayload();
    }
}
