package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:player_rotation}.
 *
 * <p>The packet sets yaw and pitch on the receiving player's own connection,
 * with an independent relative flag for each angle. Replaying the recorded
 * player's view rotation would overwrite the observer's camera, so the shared
 * viewer-local policy drops it before decoding.</p>
 */
final class Id073PlayerRotationPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x49;
    static final String IDENTIFIER = "minecraft:player_rotation";

    Id073PlayerRotationPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
