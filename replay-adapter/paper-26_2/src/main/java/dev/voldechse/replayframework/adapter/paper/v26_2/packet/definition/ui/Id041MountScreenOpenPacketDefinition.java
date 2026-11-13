package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:mount_screen_open}.
 *
 * <p>Paper sends this packet when opening a horse-family or Nautilus inventory.
 * Its container ID belongs to the receiving player's active menu sequence,
 * while the mount entity ID and inventory-column count select the menu being
 * opened. Forwarding recorded values could open an observer menu for a stale
 * container or an unrelated entity. Keep the raw packet in recordings, but
 * drop it before playback decoding.</p>
 */
final class Id041MountScreenOpenPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x29;
    static final String IDENTIFIER = "minecraft:mount_screen_open";

    Id041MountScreenOpenPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
