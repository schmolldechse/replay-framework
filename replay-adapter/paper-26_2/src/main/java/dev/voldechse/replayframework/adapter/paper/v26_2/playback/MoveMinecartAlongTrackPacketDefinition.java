package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:move_minecart_along_track}. */
final class MoveMinecartAlongTrackPacketDefinition extends CatalogPacketDefinition {

    MoveMinecartAlongTrackPacketDefinition(PacketClassification classification) {
        super("minecraft:move_minecart_along_track", classification, Behavior.IDENTITY);
    }
}

