package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:player_look_at}. */
final class PlayerLookAtPacketDefinition extends CatalogPacketDefinition {

    PlayerLookAtPacketDefinition(PacketClassification classification) {
        super("minecraft:player_look_at", classification);
    }
}

