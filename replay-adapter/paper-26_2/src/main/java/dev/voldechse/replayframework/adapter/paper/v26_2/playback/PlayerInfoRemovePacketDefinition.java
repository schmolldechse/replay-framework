package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:player_info_remove}. */
final class PlayerInfoRemovePacketDefinition extends CatalogPacketDefinition {

    PlayerInfoRemovePacketDefinition(PacketClassification classification) {
        super("minecraft:player_info_remove", classification, Behavior.IDENTITY);
    }
}

