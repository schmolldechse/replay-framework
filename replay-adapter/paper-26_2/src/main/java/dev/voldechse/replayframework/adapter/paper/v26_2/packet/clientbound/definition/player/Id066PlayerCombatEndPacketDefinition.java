package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:player_combat_end}.
 *
 * <p>The duration is a transient combat-tracker event for the recorded player.
 * It must not be applied to the observer's own connection, and it is not state
 * that a checkpoint needs to restore.</p>
 */
final class Id066PlayerCombatEndPacketDefinition
        extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x42;
    static final String IDENTIFIER = "minecraft:player_combat_end";

    Id066PlayerCombatEndPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
