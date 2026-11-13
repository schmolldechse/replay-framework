package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.chat;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;

/**
 * Viewer-local contract for {@code minecraft:player_chat}.
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
final class Id065PlayerChatPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x41;
    static final String IDENTIFIER = "minecraft:player_chat";

    Id065PlayerChatPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.configurable(
                        PacketDescriptor.PlaybackScope.SAFE));
    }

    @Override
    protected PacketRewriteResult rewriteSafe(
            Object decodedPacket,
            Paper26_2ReplayPacketRewriter context) {
        if (!(decodedPacket instanceof ClientboundPlayerChatPacket playerChat)) {
            return PacketRewriteResult.fail(
                    "decoded packet type does not match " + IDENTIFIER);
        }
        if (playerChat.filterMask().isFullyFiltered()) {
            return PacketRewriteResult.drop("fully filtered player chat has no visible replay text");
        }

        Component visibleContent = playerChat.unsignedContent();
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
}
