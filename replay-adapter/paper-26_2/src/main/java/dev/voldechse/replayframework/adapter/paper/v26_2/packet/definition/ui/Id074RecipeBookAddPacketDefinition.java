package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:recipe_book_add}.
 *
 * <p>The entries update the receiving player's known recipes and local recipe
 * book, including notifications, highlights, and replacement of that list.
 * This state belongs to the recorded player's connection, so the shared
 * viewer-local policy drops the packet before decoding its registry-backed
 * recipe data.</p>
 */
final class Id074RecipeBookAddPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x4A;
    static final String IDENTIFIER = "minecraft:recipe_book_add";

    Id074RecipeBookAddPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
