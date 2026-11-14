package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:player_position}.
 *
 * <p>This is a server correction for the receiving player's own position,
 * velocity, and rotation. Its teleport ID also belongs to that connection's
 * confirmation flow. Replaying it would move the observer and mix the
 * recording's connection state into the observer's session, so the shared
 * viewer-local policy drops it before decoding.</p>
 */
final class Id072PlayerPositionPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x48;
    static final String IDENTIFIER = "minecraft:player_position";

    Id072PlayerPositionPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
