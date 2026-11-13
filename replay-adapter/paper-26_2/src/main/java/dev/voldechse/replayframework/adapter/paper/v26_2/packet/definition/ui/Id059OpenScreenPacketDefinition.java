package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Captured viewer-local container screen policy for {@code minecraft:open_screen}.
 *
 * <p>The packet replaces the receiving player's active menu and title.
 * Playback drops it before decode so recorded UI state cannot replace the
 * observer's own open screen.</p>
 */
final class Id059OpenScreenPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x3B;
    static final String IDENTIFIER = "minecraft:open_screen";

    Id059OpenScreenPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
