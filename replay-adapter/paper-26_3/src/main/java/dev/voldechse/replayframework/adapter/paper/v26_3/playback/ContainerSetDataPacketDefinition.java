package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:container_set_data}. */
final class ContainerSetDataPacketDefinition extends CatalogPacketDefinition {

    ContainerSetDataPacketDefinition(PacketClassification classification) {
        super("minecraft:container_set_data", classification);
    }
}

