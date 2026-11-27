package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_player_inventory}.
 *
 * <p>The packet directly changes a slot in the receiving player's inventory,
 * independently of an open container. Keep it in recordings, but playback
 * drops it before decoding the ItemStack so the observer's inventory is never
 * replaced by the recorded player's state.</p>
 *
 * <p>Not part of the recipient's state track: the server does not send every
 * change of a player's own inventory or selected slot, so Argus reads both
 * every tick instead (see {@code Paper26_3SubjectStates}).</p>
 */
final class Id110SetPlayerInventoryPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x6E;
    static final String IDENTIFIER = "minecraft:set_player_inventory";

    Id110SetPlayerInventoryPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
