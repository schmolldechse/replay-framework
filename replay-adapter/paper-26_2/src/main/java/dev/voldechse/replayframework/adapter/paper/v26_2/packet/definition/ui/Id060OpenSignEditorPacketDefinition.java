package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Captured viewer-local sign editor policy for {@code minecraft:open_sign_editor}.
 *
 * <p>The packet opens an editing screen for the recorded player's sign interaction.
 * Playback drops it before decode so it cannot open an editor in the observer's
 * local world or connection.</p>
 */
final class Id060OpenSignEditorPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x3C;
    static final String IDENTIFIER = "minecraft:open_sign_editor";

    Id060OpenSignEditorPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
