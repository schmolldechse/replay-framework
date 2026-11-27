package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local trading-menu state for {@code minecraft:merchant_offers}.
 *
 * <p>The container ID identifies an open menu on one connection, while the
 * offer list and merchant progress describe that player's active merchant.
 * Forwarding recorded offers can therefore populate an unrelated viewer's
 * menu, or overwrite the viewer's live trade state. Keep the packet in the
 * recording, but drop it before decode and exclude it from checkpoints.</p>
 */
final class Id053MerchantOffersPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x35;
    static final String IDENTIFIER = "minecraft:merchant_offers";

    Id053MerchantOffersPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
