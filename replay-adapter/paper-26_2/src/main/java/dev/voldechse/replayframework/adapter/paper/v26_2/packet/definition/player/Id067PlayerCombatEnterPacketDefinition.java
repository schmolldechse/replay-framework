package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:player_combat_enter}.
 *
 * <p>This payload-free notification belongs to the recorded player's transient
 * combat state. Replaying it on the observer's connection would couple the
 * observer to that state, and it is not data a checkpoint needs to restore.</p>
 */
final class Id067PlayerCombatEnterPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x43;
    static final String IDENTIFIER = "minecraft:player_combat_enter";

    Id067PlayerCombatEnterPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
