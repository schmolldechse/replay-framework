package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.scoreboard;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_objective}.
 *
 * <p>This packet creates, updates, or removes a scoreboard objective in the
 * receiving client's scoreboard. Those objectives belong to the recorded
 * connection, so replaying them would replace or alter the observer's own
 * scoreboard. Keep the packet in the recording, but drop it before decoding
 * or forwarding it during playback.</p>
 */
final class Id108SetObjectivePacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x6C;
    static final String IDENTIFIER = "minecraft:set_objective";

    Id108SetObjectivePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
