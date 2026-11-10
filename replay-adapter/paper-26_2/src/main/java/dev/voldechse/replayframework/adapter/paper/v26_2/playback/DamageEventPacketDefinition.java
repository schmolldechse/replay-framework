package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:damage_event}. */
final class DamageEventPacketDefinition extends CatalogPacketDefinition {

    DamageEventPacketDefinition(PacketClassification classification) {
        super("minecraft:damage_event", classification, Behavior.IDENTITY);
    }
}

