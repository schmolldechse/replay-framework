package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_default_spawn_position}. */
final class SetDefaultSpawnPositionPacketDefinition extends CatalogPacketDefinition {

    SetDefaultSpawnPositionPacketDefinition(PacketClassification classification) {
        super("minecraft:set_default_spawn_position", classification);
    }
}

