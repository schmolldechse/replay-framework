package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:recipe_book_settings}.
 *
 * <p>The packet sets the receiving player's open and filtering preferences for
 * each recipe-book type. Applying the recorded player's preferences would
 * overwrite the observer's UI state, so the shared viewer-local policy drops
 * it before decoding.</p>
 */
final class Id076RecipeBookSettingsPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x4C;
    static final String IDENTIFIER = "minecraft:recipe_book_settings";

    Id076RecipeBookSettingsPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
