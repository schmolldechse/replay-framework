package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local capture and playback policy for {@code minecraft:show_dialog} in PLAY.
 *
 * <p>Dialog contents are authored for the recording's connection. Opening them for a neutral
 * observer would replace that observer's current screen, so playback drops the packet locally.</p>
 */
final class Id140ShowDialogPacketDefinition
        extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x8C;
    static final String IDENTIFIER = "minecraft:show_dialog";

    Id140ShowDialogPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
