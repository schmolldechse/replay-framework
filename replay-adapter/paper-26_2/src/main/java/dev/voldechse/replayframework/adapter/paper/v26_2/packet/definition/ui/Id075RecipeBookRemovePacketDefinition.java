package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:recipe_book_remove}.
 *
 * <p>The recipe display IDs remove entries from the receiving player's own
 * recipe book. Applying removals from the recording could erase the observer's
 * local recipe state, so the shared viewer-local policy drops this packet
 * before decoding.</p>
 */
final class Id075RecipeBookRemovePacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x4B;
    static final String IDENTIFIER = "minecraft:recipe_book_remove";

    Id075RecipeBookRemovePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
