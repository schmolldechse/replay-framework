package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_health}.
 *
 * <p>The health Float, food VarInt, and saturation Float target the receiving
 * player's own vitals HUD. Keep them in recordings but do not overwrite the
 * observer's live state. The common packet policy drops the frame before
 * decoding any field.</p>
 */
final class Id104SetHealthPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x68;
    static final String IDENTIFIER = "minecraft:set_health";

    Id104SetHealthPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
