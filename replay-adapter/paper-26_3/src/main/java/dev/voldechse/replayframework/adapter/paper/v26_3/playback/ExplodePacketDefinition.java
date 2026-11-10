package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:explode}. */
final class ExplodePacketDefinition extends CatalogPacketDefinition {

    ExplodePacketDefinition(PacketClassification classification) {
        super("minecraft:explode", classification, Behavior.EXPLOSION);
    }
}

