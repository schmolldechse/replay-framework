package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:set_cursor_item}.
 *
 * <p>The Slot may represent an empty cursor or an item stack with an arbitrary
 * version-specific data-component patch. It is persistent cursor state for
 * the receiving connection, so recorded values are dropped before the native
 * item codec can affect the replay observer's cursor.</p>
 */
final class Id098SetCursorItemPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x62;
    static final String IDENTIFIER = "minecraft:set_cursor_item";

    Id098SetCursorItemPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
