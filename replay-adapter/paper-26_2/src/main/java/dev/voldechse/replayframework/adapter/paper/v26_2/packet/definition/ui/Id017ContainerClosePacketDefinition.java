package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:container_close}.
 *
 * <p>The packet closes the menu identified by its VarInt container ID. That ID
 * belongs to the receiving connection and may be reused for an unrelated menu;
 * replaying a recorded close could therefore dismiss the observer's live
 * screen and invalidate its following state/slot synchronization. Recorded
 * closes are dropped before decode while genuine live menu traffic remains
 * owned by the observer's connection.</p>
 */
final class Id017ContainerClosePacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x11;
    static final String IDENTIFIER = "minecraft:container_close";

    Id017ContainerClosePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
