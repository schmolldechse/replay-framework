package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local capture and playback policy for {@code minecraft:clear_dialog} in PLAY.
 *
 * <p>The marker packet closes a dialog on its receiving connection. Neutral-observer playback
 * must leave dialogs opened by the observer untouched.</p>
 */
final class Id139ClearDialogPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x8B;
    static final String IDENTIFIER = "minecraft:clear_dialog";

    Id139ClearDialogPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
