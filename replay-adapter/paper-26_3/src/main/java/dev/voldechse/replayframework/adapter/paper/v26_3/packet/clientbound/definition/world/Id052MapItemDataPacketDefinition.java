package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local saved map data update for {@code minecraft:map_item_data}.
 *
 * <p>The packet updates client-side map pixels and decorations under a numeric
 * map ID. Map items and item-frame contents refer to that same ID. Replaying
 * this packet with the source ID could overwrite an observer map, while
 * changing only this packet's ID would disconnect the update from its item
 * references. Capture it for the recording, but drop it before decode until a
 * shared map-ID rewrite covers every reference.</p>
 */
final class Id052MapItemDataPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x34;
    static final String IDENTIFIER = "minecraft:map_item_data";

    Id052MapItemDataPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
