package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.chat;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientCopies;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;

/**
 * Contract for {@code minecraft:disguised_chat}.
 *
 * <p>The packet contains chat content and a bound chat type: a
 * registry-backed decoration, sender name, and optional target name. A
 * message of a public chat type is recorded and replayed; a whisper or team
 * message is neither.</p>
 */
final class Id033DisguisedChatPacketDefinition
        extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x21;
    static final String IDENTIFIER = "minecraft:disguised_chat";

    Id033DisguisedChatPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.SAFE));
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
        String text = ClientboundDisguisedChatPacket.class.cast(decodedPacket)
                .message().getString();
        return dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.Paper26_2CaptureContexts
                .withChat(base, text);
    }

    /** Private messages are never recorded; see {@link PublicChat}. */
    @Override
    public boolean suppressesRecipientState(
            Object decodedPacket,
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState state,
            long serverTick) {
        return !(PublicChat.isPublic(ClientboundDisguisedChatPacket.class.cast(decodedPacket).chatType()));
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
        return PublicChat.isPublic(
                        ClientboundDisguisedChatPacket.class.cast(decodedPacket).chatType())
                ? dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult.send(decodedPacket)
                : dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult.drop("chat message was addressed to selected players");
    }

    /** The server builds equal bytes for every recipient that hides the sender. */
    @Override
    public RecipientCopies recipientCopies() {
        return RecipientCopies.identicalPayload();
    }
}
