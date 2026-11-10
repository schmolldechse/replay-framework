package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:respawn}. */
final class RespawnPacketDefinition extends CatalogPacketDefinition {

    RespawnPacketDefinition(PacketClassification classification) {
        super("minecraft:respawn", classification);
    }
}

