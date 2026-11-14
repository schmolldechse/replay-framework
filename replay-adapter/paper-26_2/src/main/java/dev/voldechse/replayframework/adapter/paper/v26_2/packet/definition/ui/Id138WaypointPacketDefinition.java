package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local capture and playback policy for {@code minecraft:waypoint}.
 *
 * <p>Track, update, and untrack operations mutate the receiving player's locator-bar state. During
 * replay the observer must keep their own markers, so the base policy drops this packet locally.</p>
 */
final class Id138WaypointPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x8A;
    static final String IDENTIFIER = "minecraft:waypoint";

    Id138WaypointPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
