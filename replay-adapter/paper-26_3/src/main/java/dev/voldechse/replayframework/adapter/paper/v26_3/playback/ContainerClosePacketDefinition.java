package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:container_close}. */
final class ContainerClosePacketDefinition extends CatalogPacketDefinition {

    ContainerClosePacketDefinition(PacketClassification classification) {
        super("minecraft:container_close", classification);
    }
}

