package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local diagnostic toast for {@code minecraft:low_disk_space_warning}.
 *
 * <p>The source server emits this fieldless packet when it detects low disk
 * space, and the client presents it as a system toast. Replaying it would
 * report the recorded server's storage condition as if it belonged to the
 * observer's current playback environment. Keep the event in recordings for
 * diagnostics, but drop it before decode and exclude it from checkpoints.</p>
 */
final class Id051LowDiskSpaceWarningPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x33;
    static final String IDENTIFIER = "minecraft:low_disk_space_warning";

    Id051LowDiskSpaceWarningPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
