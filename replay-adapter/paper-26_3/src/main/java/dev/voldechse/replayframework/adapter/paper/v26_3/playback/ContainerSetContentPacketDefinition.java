package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:container_set_content}. */
final class ContainerSetContentPacketDefinition extends CatalogPacketDefinition {

    ContainerSetContentPacketDefinition(PacketClassification classification) {
        super("minecraft:container_set_content", classification);
    }
}

