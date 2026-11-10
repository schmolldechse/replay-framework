package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:debug/entity_value}. */
final class DebugEntityValuePacketDefinition extends CatalogPacketDefinition {

    DebugEntityValuePacketDefinition(PacketClassification classification) {
        super("minecraft:debug/entity_value", classification);
    }
}

