package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:start_configuration}. */
final class StartConfigurationPacketDefinition extends CatalogPacketDefinition {

    StartConfigurationPacketDefinition(PacketClassification classification) {
        super("minecraft:start_configuration", classification);
    }
}

