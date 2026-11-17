package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local capture and playback policy for {@code minecraft:server_links}.
 *
 * <p>The links populate the server-links view in the pause menu. They belong to the recorded
 * connection, so neutral-observer playback keeps the observer's own menu unchanged.</p>
 */
final class Id137ServerLinksPacketDefinition
        extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x89;
    static final String IDENTIFIER = "minecraft:server_links";

    Id137ServerLinksPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
