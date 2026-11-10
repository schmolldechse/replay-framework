package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:cooldown}. */
final class CooldownPacketDefinition extends CatalogPacketDefinition {

    CooldownPacketDefinition(PacketClassification classification) {
        super("minecraft:cooldown", classification);
    }
}

