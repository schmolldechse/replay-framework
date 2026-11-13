package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:player_combat_kill}.
 *
 * <p>The recorded victim receives this packet with their entity ID and death
 * message to open their own death screen. The server broadcasts any public
 * death message separately, so replaying this packet would incorrectly apply
 * the victim's death state to the observer.</p>
 */
final class Id068PlayerCombatKillPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x44;
    static final String IDENTIFIER = "minecraft:player_combat_kill";

    Id068PlayerCombatKillPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
