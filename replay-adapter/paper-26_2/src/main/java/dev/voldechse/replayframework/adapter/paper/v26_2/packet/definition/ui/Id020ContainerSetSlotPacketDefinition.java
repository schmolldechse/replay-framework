package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:container_set_slot}.
 *
 * <p>The packet updates a signed-short slot in one VarInt container/state pair
 * with an optional, registry-aware item stack. Slot layout and state sequencing
 * belong to the active connection-local menu; the same packet is also used for
 * each indexed item when a large full-content packet falls back to individual
 * updates. Its stack may carry arbitrary registered data components. Recorded
 * slot updates are consequently dropped before decode so they cannot corrupt
 * the observer's live menu or cursor/inventory synchronization.</p>
 */
final class Id020ContainerSetSlotPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x14;
    static final String IDENTIFIER = "minecraft:container_set_slot";

    Id020ContainerSetSlotPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
