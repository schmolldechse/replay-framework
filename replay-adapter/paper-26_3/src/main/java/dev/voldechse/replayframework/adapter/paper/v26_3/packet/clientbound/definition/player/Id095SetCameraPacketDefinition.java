package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:set_camera}.
 *
 * <p>The packet persistently selects the entity followed by the receiving
 * client's camera. Applying a recorded camera target would take control of the
 * replay observer's view, so this viewer-local state is dropped before its
 * entity ID is decoded.</p>
 */
final class Id095SetCameraPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x5F;
    static final String IDENTIFIER = "minecraft:set_camera";

    Id095SetCameraPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
