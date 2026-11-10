package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:boss_event}. */
final class BossEventPacketDefinition extends CatalogPacketDefinition {

    BossEventPacketDefinition(PacketClassification classification) {
        super("minecraft:boss_event", classification, Behavior.BOSS_EVENT);
    }
}

