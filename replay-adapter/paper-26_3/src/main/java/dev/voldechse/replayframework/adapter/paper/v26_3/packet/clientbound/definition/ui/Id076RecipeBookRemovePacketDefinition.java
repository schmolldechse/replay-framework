package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:recipe_book_remove}.
 *
 * <p>The recipe display IDs remove entries from the receiving player's own
 * recipe book. Applying removals from the recording could erase the observer's
 * local recipe state, so the shared viewer-local policy drops this packet
 * before decoding.</p>
 */
final class Id076RecipeBookRemovePacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x4C;
    static final String IDENTIFIER = "minecraft:recipe_book_remove";

    Id076RecipeBookRemovePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
