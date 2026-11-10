package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:projectile_power}. */
final class ProjectilePowerPacketDefinition extends CatalogPacketDefinition {

    ProjectilePowerPacketDefinition(PacketClassification classification) {
        super("minecraft:projectile_power", classification, Behavior.IDENTITY);
    }
}

