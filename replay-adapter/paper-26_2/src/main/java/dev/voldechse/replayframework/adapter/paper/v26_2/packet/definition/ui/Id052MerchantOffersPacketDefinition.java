package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local trading-menu state for {@code minecraft:merchant_offers}.
 *
 * <p>The container ID identifies an open menu on one connection, while the
 * offer list and merchant progress describe that player's active merchant.
 * Forwarding recorded offers can therefore populate an unrelated viewer's
 * menu, or overwrite the viewer's live trade state. Keep the packet in the
 * recording, but drop it before decode and exclude it from checkpoints.</p>
 */
final class Id052MerchantOffersPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x34;
    static final String IDENTIFIER = "minecraft:merchant_offers";

    Id052MerchantOffersPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
