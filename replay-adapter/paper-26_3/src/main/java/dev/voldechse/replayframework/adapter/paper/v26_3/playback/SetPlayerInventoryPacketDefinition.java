package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_player_inventory}. */
final class SetPlayerInventoryPacketDefinition extends CatalogPacketDefinition {

    SetPlayerInventoryPacketDefinition(PacketClassification classification) {
        super("minecraft:set_player_inventory", classification);
    }
}

