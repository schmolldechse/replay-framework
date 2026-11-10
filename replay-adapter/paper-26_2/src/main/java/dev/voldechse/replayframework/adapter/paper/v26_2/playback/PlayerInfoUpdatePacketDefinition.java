package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:player_info_update}. */
final class PlayerInfoUpdatePacketDefinition extends CatalogPacketDefinition {

    PlayerInfoUpdatePacketDefinition(PacketClassification classification) {
        super("minecraft:player_info_update", classification, Behavior.IDENTITY);
    }
}

