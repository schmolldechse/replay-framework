package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientState;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_health}.
 *
 * <p>The health Float, food VarInt, and saturation Float target the receiving
 * player's own vitals HUD. Keep them in recordings but do not overwrite the
 * observer's live state. The common packet policy drops the frame before
 * decoding any field.</p>
 */
final class Id106SetHealthPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x6A;
    static final String IDENTIFIER = "minecraft:set_health";

    Id106SetHealthPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

    /** The recipient's health, food and saturation. */
    @Override
    public RecipientState recipientState() {
        return RecipientState.always();
    }
}
