package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_player_inventory}.
 *
 * <p>The packet directly changes a slot in the receiving player's inventory,
 * independently of an open container. Keep it in recordings, but playback
 * drops it before decoding the ItemStack so the observer's inventory is never
 * replaced by the recorded player's state.</p>
 */
final class Id108SetPlayerInventoryPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x6C;
    static final String IDENTIFIER = "minecraft:set_player_inventory";

    Id108SetPlayerInventoryPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
