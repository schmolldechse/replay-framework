package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:container_set_slot}. */
final class ContainerSetSlotPacketDefinition extends CatalogPacketDefinition {

    ContainerSetSlotPacketDefinition(PacketClassification classification) {
        super("minecraft:container_set_slot", classification);
    }
}

