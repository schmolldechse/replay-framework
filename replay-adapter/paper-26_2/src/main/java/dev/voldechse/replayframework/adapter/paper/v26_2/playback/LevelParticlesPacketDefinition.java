package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:level_particles}. */
final class LevelParticlesPacketDefinition extends CatalogPacketDefinition {

    LevelParticlesPacketDefinition(PacketClassification classification) {
        super("minecraft:level_particles", classification);
    }
}

