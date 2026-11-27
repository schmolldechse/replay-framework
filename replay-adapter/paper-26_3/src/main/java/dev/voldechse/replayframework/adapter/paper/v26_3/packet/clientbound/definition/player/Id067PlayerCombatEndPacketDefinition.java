package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:player_combat_end}.
 *
 * <p>The duration is a transient combat-tracker event for the recorded player.
 * It must not be applied to the observer's own connection, and it is not state
 * that a checkpoint needs to restore.</p>
 */
final class Id067PlayerCombatEndPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x43;
    static final String IDENTIFIER = "minecraft:player_combat_end";

    Id067PlayerCombatEndPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
