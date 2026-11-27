package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:player_combat_kill}.
 *
 * <p>The recorded victim receives this packet with their entity ID and death
 * message to open their own death screen. The server broadcasts any public
 * death message separately, so replaying this packet would incorrectly apply
 * the victim's death state to the observer.</p>
 */
final class Id069PlayerCombatKillPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x45;
    static final String IDENTIFIER = "minecraft:player_combat_kill";

    Id069PlayerCombatKillPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
