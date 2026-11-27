package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local recording policy for {@code minecraft:update_recipes}.
 *
 * <p>This packet replaces the receiving client's recipe-property filters and
 * stonecutter choices. It belongs to the recorded connection, not to the
 * observer's scene. The inherited {@link ClientboundPacketDefinition} policy retains the
 * raw frame during capture and drops it before decoding its registry-backed
 * payload during neutral-observer playback.</p>
 */
final class Id133UpdateRecipesPacketDefinition
        extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x85;
    static final String IDENTIFIER = "minecraft:update_recipes";

    Id133UpdateRecipesPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
