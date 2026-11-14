package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:set_camera}.
 *
 * <p>The packet persistently selects the entity followed by the receiving
 * client's camera. Applying a recorded camera target would take control of the
 * replay observer's view, so this viewer-local state is dropped before its
 * entity ID is decoded.</p>
 */
final class Id093SetCameraPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x5D;
    static final String IDENTIFIER = "minecraft:set_camera";

    Id093SetCameraPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
