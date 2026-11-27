package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.chat;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientCopies;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;

/**
 * Replay presentation contract for {@code minecraft:player_chat}.
 *
 * <p>The packet carries the sender's signed-message chain, the recipient's
 * last-seen acknowledgements, filtering state, and a bound chat decoration.
 * Those sequence numbers and signatures belong to the source connection and
 * cannot safely be applied to a viewer whose captured messages may have been
 * filtered or whose replay may start mid-stream. Keep the visible text under
 * the recording's configurable chat policy, then emit it as the existing
 * unsigned disguised-chat presentation packet while preserving its bound
 * decoration.</p>
 */
final class Id066PlayerChatPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x42;
    static final String IDENTIFIER = "minecraft:player_chat";

    Id066PlayerChatPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.configurable(
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
        ClientboundPlayerChatPacket packet = ClientboundPlayerChatPacket.class.cast(decodedPacket);
        final String text;
        if (packet.filterMask().isFullyFiltered()) {
            text = "";
        } else if (packet.unsignedContent().isPresent()) {
            text = packet.unsignedContent().orElseThrow().getString();
        } else {
            String filtered = packet.filterMask().apply(packet.body().content());
            text = filtered == null ? "" : filtered;
        }
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.CaptureContexts
                .withChat(base, text);
    }

    @Override
    protected PacketRewriteResult rewriteSafe(
            Object decodedPacket,
            Paper26_3ReplayPacketRewriter context) {
        if (!(decodedPacket instanceof ClientboundPlayerChatPacket playerChat)) {
            return PacketRewriteResult.fail(
                    "decoded packet type does not match " + IDENTIFIER);
        }
        if (!PublicChat.isPublic(playerChat.chatType())) {
            return PacketRewriteResult.drop("chat message was addressed to selected players");
        }
        if (playerChat.filterMask().isFullyFiltered()) {
            return PacketRewriteResult.drop("fully filtered player chat has no visible replay text");
        }

        // Paper 26.3 models the optional unsigned content as an Optional.
        Component visibleContent = playerChat.unsignedContent().orElse(null);
        if (visibleContent == null) {
            visibleContent = playerChat.filterMask()
                    .applyWithFormatting(playerChat.body().content());
        }
        if (visibleContent == null) {
            return PacketRewriteResult.drop("player chat filter removed all visible replay text");
        }

        return PacketRewriteResult.sendAs(
                new ClientboundDisguisedChatPacket(visibleContent, playerChat.chatType()),
                Id033DisguisedChatPacketDefinition.PACKET_ID);
    }

    /** Private messages are never recorded; see {@link PublicChat}. */
    @Override
    public boolean suppressesRecipientState(
            Object decodedPacket,
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState state,
            long serverTick) {
        return !(PublicChat.isPublic(ClientboundPlayerChatPacket.class.cast(decodedPacket).chatType()));
    }

    /** Public chat is not a place: every recording of the world accepts it. */
    @Override
    public boolean worldWide() {
        return true;
    }

    /** Index and packed signatures are per recipient; sender, its message index and the signed body name the message. */
    @Override
    public RecipientCopies recipientCopies() {
        return RecipientCopies.keyedBy(
                net.minecraft.network.protocol.game.ClientboundPlayerChatPacket.class,
                packet -> java.util.List.of(
                        packet.sender(),
                        packet.index(),
                        packet.body().content(),
                        packet.body().timeStamp(),
                        packet.body().salt()));
    }
}
