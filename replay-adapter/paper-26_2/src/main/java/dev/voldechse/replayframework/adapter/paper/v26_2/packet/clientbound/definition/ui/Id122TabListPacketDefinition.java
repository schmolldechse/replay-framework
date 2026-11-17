package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local state contract for {@code minecraft:tab_list}.
 *
 * <p>The header and footer customize the recorded connection's player-list UI and must not
 * replace the observer's own list presentation.</p>
 */
final class Id122TabListPacketDefinition
        extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x7A;
    static final String IDENTIFIER = "minecraft:tab_list";

    Id122TabListPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
