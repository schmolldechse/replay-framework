package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:player_position}.
 *
 * <p>This is a server correction for the receiving player's own position,
 * velocity, and rotation. Its teleport ID also belongs to that connection's
 * confirmation flow. Replaying it would move the observer and mix the
 * recording's connection state into the observer's session, so the shared
 * viewer-local policy drops it before decoding.</p>
 *
 * <p>The first absolute correction after a {@code minecraft:respawn} that
 * discards the client's entity data is the spawn placement of the receiving
 * player's new client entity. Other tracking players learn about that
 * replacement through {@code remove_entities} and {@code add_entity}, but the
 * respawning connection itself never receives them. Capture therefore derives
 * exactly that pair for the connection's own entity from this packet's
 * position and rotation, so the neutral stream replaces the scene entity even
 * when no other player observed the respawn. Without the replacement a client
 * entity that played its death animation keeps that state permanently.</p>
 */
final class Id073PlayerPositionPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x49;
    static final String IDENTIFIER = "minecraft:player_position";

    Id073PlayerPositionPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
