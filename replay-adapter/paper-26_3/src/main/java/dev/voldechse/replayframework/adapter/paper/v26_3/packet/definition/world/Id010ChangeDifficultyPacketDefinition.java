package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:change_difficulty}.
 *
 * <p>The difficulty and its lock flag describe state installed in the
 * receiving client's connection. They remain useful recording data, but
 * replaying them would overwrite the neutral observer's live server state.
 * The {@link PacketDefinition} base therefore drops every payload before
 * decode.</p>
 */
final class Id010ChangeDifficultyPacketDefinition
        extends PacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x0A;
    static final String IDENTIFIER = "minecraft:change_difficulty";

    Id010ChangeDifficultyPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
