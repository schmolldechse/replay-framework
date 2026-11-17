package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:player_abilities}.
 *
 * <p>The flags and movement speeds configure the player on the receiving
 * connection. Replaying a recorded player's abilities would change the
 * observer's own flight, invulnerability, or movement settings, so the base
 * definition drops this packet before decoding its payload.</p>
 */
final class Id064PlayerAbilitiesPacketDefinition
        extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x40;
    static final String IDENTIFIER = "minecraft:player_abilities";

    Id064PlayerAbilitiesPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
