package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:player_rotation}.
 *
 * <p>The packet sets yaw and pitch on the receiving player's own connection,
 * with an independent relative flag for each angle. Replaying the recorded
 * player's view rotation would overwrite the observer's camera, so the shared
 * viewer-local policy drops it before decoding.</p>
 */
final class Id074PlayerRotationPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x4A;
    static final String IDENTIFIER = "minecraft:player_rotation";

    Id074PlayerRotationPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
