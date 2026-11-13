package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local crafting preview policy for {@code minecraft:place_ghost_recipe}.
 *
 * <p>The packet fills a container UI with a recipe preview. Playback drops it
 * before decode so the recorded player's recipe and ghost slots cannot replace
 * the observer's active menu state.</p>
 */
final class Id063PlaceGhostRecipePacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x3F;
    static final String IDENTIFIER = "minecraft:place_ghost_recipe";

    Id063PlaceGhostRecipePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
