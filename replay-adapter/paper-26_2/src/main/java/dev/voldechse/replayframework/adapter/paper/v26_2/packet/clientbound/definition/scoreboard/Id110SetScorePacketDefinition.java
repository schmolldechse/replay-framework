package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.scoreboard;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_score}.
 *
 * <p>The score owner is arbitrary scoreboard text rather than an entity ID.
 * The score and its optional display formatting belong to the recorded
 * connection's scoreboard, so retain this packet in recordings but drop it
 * before decoding or forwarding it during playback.</p>
 */
final class Id110SetScorePacketDefinition
        extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x6E;
    static final String IDENTIFIER = "minecraft:set_score";

    Id110SetScorePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
