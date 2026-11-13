package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:clear_titles}.
 *
 * <p>The packet always clears the receiving client's title and subtitle. Its
 * single flag selects whether the client's fade-in, stay and fade-out timings
 * are reset as well; the action bar is a separate protocol feature. Both
 * variants mutate only UI state owned by the recorded connection, so applying
 * either one would erase or alter the neutral observer's live presentation.
 * The {@link PacketDefinition} base therefore drops every recorded payload
 * before decode while the outbound gate continues to allow the observer's
 * genuine live UI packets.</p>
 */
final class Id014ClearTitlesPacketDefinition
        extends PacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x0E;
    static final String IDENTIFIER = "minecraft:clear_titles";

    Id014ClearTitlesPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
