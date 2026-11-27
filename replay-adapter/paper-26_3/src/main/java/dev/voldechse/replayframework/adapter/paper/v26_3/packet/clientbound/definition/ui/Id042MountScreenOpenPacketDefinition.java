package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

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
final class Id042MountScreenOpenPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x2A;
    static final String IDENTIFIER = "minecraft:mount_screen_open";

    Id042MountScreenOpenPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
