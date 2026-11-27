package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:recipe_book_add}.
 *
 * <p>The entries update the receiving player's known recipes and local recipe
 * book, including notifications, highlights, and replacement of that list.
 * This state belongs to the recorded player's connection, so the shared
 * viewer-local policy drops the packet before decoding its registry-backed
 * recipe data.</p>
 */
final class Id075RecipeBookAddPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x4B;
    static final String IDENTIFIER = "minecraft:recipe_book_add";

    Id075RecipeBookAddPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
