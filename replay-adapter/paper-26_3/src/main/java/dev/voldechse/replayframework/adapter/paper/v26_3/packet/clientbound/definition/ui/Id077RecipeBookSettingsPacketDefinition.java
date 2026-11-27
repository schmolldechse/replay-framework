package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:recipe_book_settings}.
 *
 * <p>The packet sets the receiving player's open and filtering preferences for
 * each recipe-book type. Applying the recorded player's preferences would
 * overwrite the observer's UI state, so the shared viewer-local policy drops
 * it before decoding.</p>
 */
final class Id077RecipeBookSettingsPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x4D;
    static final String IDENTIFIER = "minecraft:recipe_book_settings";

    Id077RecipeBookSettingsPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
