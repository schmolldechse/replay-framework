package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local crafting preview policy for {@code minecraft:place_ghost_recipe}.
 *
 * <p>The packet fills a container UI with a recipe preview. Playback drops it
 * before decode so the recorded player's recipe and ghost slots cannot replace
 * the observer's active menu state.</p>
 */
final class Id064PlaceGhostRecipePacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x40;
    static final String IDENTIFIER = "minecraft:place_ghost_recipe";

    Id064PlaceGhostRecipePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
