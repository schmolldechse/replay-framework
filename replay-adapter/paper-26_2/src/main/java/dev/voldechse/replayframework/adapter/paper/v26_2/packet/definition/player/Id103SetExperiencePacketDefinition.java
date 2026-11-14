package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_experience}.
 *
 * <p>The progress float, level VarInt, and total-experience VarInt describe
 * the receiving player's own experience HUD. They remain recording data but
 * must not overwrite the neutral observer's progress or level. The common
 * packet policy drops this packet before decoding its fields.</p>
 */
final class Id103SetExperiencePacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x67;
    static final String IDENTIFIER = "minecraft:set_experience";

    Id103SetExperiencePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
