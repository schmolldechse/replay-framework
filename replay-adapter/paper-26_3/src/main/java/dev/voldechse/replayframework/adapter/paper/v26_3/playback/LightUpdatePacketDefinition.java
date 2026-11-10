package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:light_update}. */
final class LightUpdatePacketDefinition extends CatalogPacketDefinition {

    LightUpdatePacketDefinition(PacketClassification classification) {
        super("minecraft:light_update", classification);
    }
}

