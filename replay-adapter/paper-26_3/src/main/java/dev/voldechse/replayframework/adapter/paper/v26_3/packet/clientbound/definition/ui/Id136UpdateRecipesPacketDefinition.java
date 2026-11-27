package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local recording policy for {@code minecraft:update_recipes}.
 *
 * <p>This packet replaces the receiving client's recipe-property filters and
 * stonecutter choices. It belongs to the recorded connection, not to the
 * observer's scene. The inherited {@link ClientboundPacketDefinition} policy retains the
 * raw frame during capture and drops it before decoding its registry-backed
 * payload during neutral-observer playback.</p>
 */
final class Id136UpdateRecipesPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x88;
    static final String IDENTIFIER = "minecraft:update_recipes";

    Id136UpdateRecipesPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
