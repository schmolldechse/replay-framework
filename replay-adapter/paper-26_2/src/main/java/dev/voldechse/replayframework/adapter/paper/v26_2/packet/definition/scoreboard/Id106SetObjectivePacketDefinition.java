package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.scoreboard;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_objective}.
 *
 * <p>This packet creates, updates, or removes a scoreboard objective in the
 * receiving client's scoreboard. Those objectives belong to the recorded
 * connection, so replaying them would replace or alter the observer's own
 * scoreboard. Keep the packet in the recording, but drop it before decoding
 * or forwarding it during playback.</p>
 */
final class Id106SetObjectivePacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x6A;
    static final String IDENTIFIER = "minecraft:set_objective";

    Id106SetObjectivePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
